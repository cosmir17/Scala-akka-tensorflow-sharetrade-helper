import java.time.LocalDate
import java.time.chrono.ChronoLocalDate
import java.time.format.DateTimeFormatter

import SharePriceGetter._
import akka.actor.{ActorLogging, ActorRef}
import akka.pattern.pipe
import akka.persistence.{PersistentActor, RecoveryCompleted}

import scala.collection.immutable.{HashMap, TreeMap}
import scala.concurrent.{ExecutionContext, Future}

object SharePriceGetter {
  case class RequestStockPrice(stockName: String, from: LocalDate, to: LocalDate)
  case class StockDataResponse(stockName: String, sharePrices: TreeMap[LocalDate, Double])

  case class Event(stockName: String, sharePrices: HashMap[LocalDate, Double])
  type SharePrices = HashMap[LocalDate, Double]
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
        .mapTo[SharePrices]
        .map(prices => (originalSender, Event(name, prices))) pipeTo self
    case (originalSender: ActorRef, Event(name, newSharePrices)) =>
      originalSender ! StockDataResponse(name, TreeMap(newSharePrices.toArray: _*))
      persist(Event(name, newSharePrices)) { e =>
        context.become(queried(HashMap(e.stockName -> e.sharePrices)))
        log.info(s"Receive command, persisted stock name : ${e.stockName} & shareprices :${e.sharePrices}")
      }
  }

  private def queried(storedStockData: HashMap[String, SharePrices]): Receive = {
    case RequestStockPrice(name, from, to) =>
      val originalSender = sender()
      val newSharePrices = queryData(name, from, to, storedStockData).mapTo[SharePrices]
      newSharePrices.map(prices => (originalSender, Event(name, prices))) pipeTo self
    case (originalSender: ActorRef, Event(name, newSharePrices)) =>
      originalSender ! StockDataResponse(name, TreeMap(newSharePrices.toArray: _*))
      persist(Event(name, newSharePrices)) { e =>
        updateStockMapIfTheresChange(storedStockData, e)
      }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
      log.info("Recovery is finished")
    case Event(name, sharePrices) =>
      val stocks = HashMap(name -> sharePrices)
      log.info(s"Recovery for ${stocks.foreach(entry => print(s"stock name : ${entry._1} "))}")
      context.become(queried(stocks))
  }

  private def updateStockMapIfTheresChange(storedStockData: HashMap[String, SharePrices], e: Event) = {
    val updatedStockMap = storedStockData.get(e.stockName)
      .foldLeft(storedStockData + (e.stockName -> e.sharePrices))( (_, specificSharePrices) => {
        val newlyAddedData = HashMap(e.sharePrices.keySet.diff(specificSharePrices.keySet).map(key => key -> e.sharePrices(key)).toSeq: _*)
        val newlyMergedOneStockData = specificSharePrices.merged(newlyAddedData)({ case ((k1, v1), (_, _)) => (k1, v1) })
        val deleted = storedStockData - e.stockName
        deleted + (e.stockName -> newlyMergedOneStockData)
      })
    context.become(queried(updatedStockMap))
  }

  /**
    * Yahoo finance API no longer works and I am faking a http querie.
    * @param stockName
    * @param from
    * @param to
    * @param presentData
    * @return
    */
  private def queryData(stockName: String, from: LocalDate, to: LocalDate, presentData: HashMap[String, SharePrices])
  : Future[SharePrices] =
    Future { //faking a http query
      import scala.io.Source
      import shapeless.{HNil, ::}

      val filename = "MSFT-stock-prices-revised.txt"
      val priceDateList: List[Option[LocalDate]:: Option[Double]:: HNil] =
        for (line <- Source.fromResource(filename).getLines.toList)
          yield line.split(",").map(_.trim()).toSeq match {
          case Seq(price, date) => parseDate(date) :: parseDouble(price) :: HNil
          case Seq(price, date, _*) => parseDate(date) :: parseDouble(price) :: HNil
          case _ => None :: None :: HNil
        }
      val parsed = priceDateList.flatMap {
        case Some(date: LocalDate) :: Some(price: Double) :: _ => Some(date, price)
        case _ => None
      }
      HashMap(parsed: _*)
    }

  private def parseDouble(s: String) = try { Some(s.toDouble) } catch { case _: Throwable => None }
  private def parseDate(s: String) = {
    val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    try { Some(LocalDate.parse(s, dtf)) } catch { case _: Throwable => None }
  }
}