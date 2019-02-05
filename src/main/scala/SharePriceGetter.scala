import java.time.LocalDate
import java.time.chrono.ChronoLocalDate

import SharePriceGetter._
import akka.actor.SupervisorStrategy.{Escalate, Restart, Resume, Stop}
import akka.actor.{ActorLogging, ActorRef, OneForOneStrategy}
import akka.http.impl.engine.HttpIdleTimeoutException
import akka.pattern.pipe
import akka.persistence.{PersistentActor, RecoveryCompleted}

import scala.collection.immutable.HashMap
import scala.collection.immutable.TreeMap
import scala.concurrent.{ExecutionContext, Future}

object SharePriceGetter {
  case class RequestStockPrice(stockName: String, from: LocalDate, to: LocalDate)
  case class StockDataResponse(stockName: String, sharePrices: TreeMap[LocalDate, Double])

  case class Event(stockName: String, sharePrices: HashMap[LocalDate, Double])

  val strategy = OneForOneStrategy() {
    case _: HttpIdleTimeoutException => Restart
    case _: ArithmeticException      => Resume
    case _: NullPointerException     => Restart
    case _: IllegalArgumentException => Stop
    case _: Exception                => Escalate
  }
}

class SharePriceGetter extends PersistentActor with ActorLogging {
  implicit val ex : ExecutionContext = context.dispatcher
  implicit val localDateOrdering: Ordering[LocalDate] = Ordering.by(identity[ChronoLocalDate])

  //get it by dividing period (different routees) and merge them together.
  override def persistenceId: String = "Share-price-getter"

  override def receiveCommand: Receive = {
    case RequestStockPrice(name, from, to) =>
      val originalSender = sender()
      queryData(name, from, to, HashMap())
        .mapTo[HashMap[LocalDate, Double]]
        .map(prices => (originalSender, Event(name, prices))) pipeTo self
    case (originalSender: ActorRef, Event(name, newSharePrices)) =>
      originalSender ! StockDataResponse(name, TreeMap(newSharePrices.toArray: _*))
      persist(Event(name, newSharePrices)) { e =>
        context.become(queried(HashMap(e.stockName -> e.sharePrices)))
        log.info(s"Receive command, persisted stock name : ${e.stockName} & shareprices :${e.sharePrices}")
      }
  }

  private def queried(storedStockData: HashMap[String, HashMap[LocalDate, Double]]): Receive = {
    case RequestStockPrice(name, from, to) =>
      val originalSender = sender()
      val newSharePrices = queryData(name, from, to, storedStockData).mapTo[HashMap[LocalDate, Double]]
      newSharePrices.map(prices => (originalSender, Event(name, prices))) pipeTo self
    case (originalSender: ActorRef, Event(name, newSharePrices)) =>
      originalSender ! StockDataResponse(name, TreeMap(newSharePrices.toArray: _*))

      persist(Event(name, newSharePrices)) { e =>
        val oneStockStoredDataOption: Option[HashMap[LocalDate, Double]] = storedStockData.get(e.stockName)
        if(oneStockStoredDataOption.nonEmpty) {
          val newlyAddedDataOption = oneStockStoredDataOption
            .map(presentStockData =>
              HashMap(e.sharePrices.keySet
                .diff(presentStockData.keySet)
                .map(key => key -> e.sharePrices(key)).toSeq: _*))

          val newlyMergedOneStockData: Option[HashMap[LocalDate, Double]] = for {
            oneStockStoredData <- oneStockStoredDataOption
            newlyAddedData <- newlyAddedDataOption
          } yield oneStockStoredData.merged(newlyAddedData)({ case ((k1, v1), (_, _)) => (k1, v1) })

          val updatedStockDataSet: Option[HashMap[String, HashMap[LocalDate, Double]]] = newlyMergedOneStockData.map(newStockEntry => {
            val deleted = storedStockData - e.stockName
            deleted + (e.stockName -> newStockEntry)
          })
        updatedStockDataSet.foreach(updated => context.become(queried(updated)))
        }
        else context.become(queried(storedStockData + (e.stockName -> e.sharePrices)))
      }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
      log.info("Recovery is finished")
    case Event(name, sharePrices) =>
      val stocks = HashMap(name -> sharePrices)
      log.info(s"Recovery for ${stocks.foreach(entry => println(s"stock name : ${entry._1}    value : ${entry._2}"))}")
      context.become(queried(stocks))
  }

  /**
    * Yahoo finance API no longer works so I am generating a sequence of numbers.
    * @param stockName
    * @param from
    * @param to
    * @param presentData
    * @return
    */
  private def queryData(stockName: String, from: LocalDate, to: LocalDate, presentData: HashMap[String, HashMap[LocalDate, Double]])
  : Future[HashMap[LocalDate, Double]] =
    Future {
      val days = from.toEpochDay.to(to.toEpochDay).map(LocalDate.ofEpochDay)
      val prices = days.indices.zip(days).map(d => (d._2, d._1.toDouble * 10))
      HashMap(prices: _*)
    }
}