package com.lei.orderpay_detect

import java.net.URL

import com.lei.orderpay_detect.OrderTimeoutWithoutCep.getClass
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.co.CoProcessFunction
import org.apache.flink.streaming.api.scala._
import org.apache.flink.util.Collector

/**
 * @Author: Lei
 * @E-mail: 843291011@qq.com
 * @Date: Created in 4:57 下午 2020/4/25
 * @Version: 1.0
 * @Modified By:
 * @Description:
 */

/**
 * 订单支付实时对账
 *
 * 基本需求：
 *    用户下单并支付后，应查询到账信息，进行实时对账
 *    如果有不匹配的支付信息或者到账信息，输出提示信息
 * 解决思路：
 *    从两条流中分别读取订单支付信息和到账信息，合并处理
 *    用connect连接合并两条流，用coProcessFunction做匹配处理
 *
 * 来自两条流的订单交易匹配
 */

// 定义接收流数据事件的样例类
case class ReceiptEvent(txId:String, payChannel:String, eventTime:Long)

object TxMacthDetect {

  // 定义侧输出数据流tag
  val unmatchedPays = new OutputTag[OrderEvent]("unmatchedPays")
  val unmatchedReceipts = new OutputTag[ReceiptEvent]("unmatchedReceipts")

  def main(args: Array[String]): Unit = {
    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    // 读取订单事件流
    val resource: URL = getClass.getResource("/OrderLog.csv")
    //val orderEventStream: KeyedStream[OrderEvent, String] = env.readTextFile(resource.getPath)
    val orderEventStream: KeyedStream[OrderEvent, String] = env.socketTextStream("localhost", 7777)
      .map(data => {
        val dataArray: Array[String] = data.split(",")
        OrderEvent(dataArray(0).trim.toLong, dataArray(1).trim, dataArray(2).trim, dataArray(3).trim.toLong)
      })
      .filter(_.txId != "")
      // 因为数据事件时间是有序的
      .assignAscendingTimestamps(_.eventTime * 1000L)
      .keyBy(_.txId)

    // 读取支付到账事件流
    val receiptResource: URL = getClass.getResource("/ReceiptLog.csv")
    //val receiptEventStream = env.readTextFile(receiptResource.getPath)
    val receiptEventStream = env.socketTextStream("localhost", 8888)
    .map(data => {
        val dataArray: Array[String] = data.split(",")
        ReceiptEvent(dataArray(0).trim, dataArray(1).trim, dataArray(2).toLong)
      })
      .assignAscendingTimestamps(_.eventTime * 1000L)
      .keyBy(_.txId)

    // 将两条流连接起来，共同处理
    val processedStream = orderEventStream.connect(receiptEventStream)
      .process(new TxPayMatch())

    processedStream.print("matched")
    processedStream.getSideOutput(unmatchedPays).print("unmatchedPays")
    processedStream.getSideOutput(unmatchedReceipts).print("unmatchReceipts")

    env.execute("tx match job")
  }
  class TxPayMatch() extends CoProcessFunction[OrderEvent, ReceiptEvent, (OrderEvent, ReceiptEvent)]{

    // 定义状态来保存已经到达的订单支付事件和到账事件
    lazy val payState: ValueState[OrderEvent] = getRuntimeContext.getState(new ValueStateDescriptor[OrderEvent]("pay-state", classOf[OrderEvent]))
    lazy val receiptState: ValueState[ReceiptEvent] = getRuntimeContext.getState(new ValueStateDescriptor[ReceiptEvent]("receipt-state", classOf[ReceiptEvent]))

    // 订单支付事件数据的处理
    override def processElement1(pay: OrderEvent, ctx: CoProcessFunction[OrderEvent, ReceiptEvent, (OrderEvent, ReceiptEvent)]#Context, out: Collector[(OrderEvent, ReceiptEvent)]): Unit = {
      // 判断有滑对应的到账事件
      val receipt: ReceiptEvent = receiptState.value()
      if (receipt != null) {
        // 如果已经有receipt，在主流输出匹配信息
        out.collect((pay, receipt))
        receiptState.clear()
      } else {
        // 如果还没到，那么把pay存入状态，并且注册一个定时器等待
        payState.update(pay)
        ctx.timerService().registerEventTimeTimer(pay.eventTime * 1000L + 5000L)
      }

    }

    // 到账事件的处理
    override def processElement2(receipt: ReceiptEvent, ctx: CoProcessFunction[OrderEvent, ReceiptEvent, (OrderEvent, ReceiptEvent)]#Context, out: Collector[(OrderEvent, ReceiptEvent)]): Unit = {
      // 同样的处理流程
      // 因为我们在前面就对交易id进行了分组，所以两条流中同样的交易id会在同一个分区中
      val pay = payState.value()
      if (pay != null){
        out.collect(pay, receipt)
        payState.clear()
      } else {
        receiptState.update(receipt)
        ctx.timerService().registerEventTimeTimer(receipt.eventTime * 1000L + 5000L)
      }
    }

    override def onTimer(timestamp: Long, ctx: CoProcessFunction[OrderEvent, ReceiptEvent, (OrderEvent, ReceiptEvent)]#OnTimerContext, out: Collector[(OrderEvent, ReceiptEvent)]): Unit = {
      // 到时间了，如果还没有收到某个事件，那么输出报警信息
      if (payState.value() != null ){
        // recipt没来，输出pay到侧输出流
        ctx.output(unmatchedPays, payState.value())
      }

      if (receiptState.value() != null){
        ctx.output(unmatchedReceipts, receiptState.value())
      }

      payState.clear()
      receiptState.clear()
    }
  }
}


