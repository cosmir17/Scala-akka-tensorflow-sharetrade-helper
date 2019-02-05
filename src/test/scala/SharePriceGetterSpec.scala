import java.time.LocalDate
import java.time.chrono.ChronoLocalDate

import SharePriceGetter.{RequestStockPrice, StockDataResponse}
import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.collection.immutable.TreeMap

class SharePriceGetterSpec extends TestKit(ActorSystem("SharePriceGetter")) with ImplicitSender with WordSpecLike with BeforeAndAfterAll {

  implicit val localDateOrdering: Ordering[LocalDate] = Ordering.by(identity[ChronoLocalDate])

  override protected def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "Share Price Getter" should {
    "query lloyd share data from 10 July 2001 to 13 July 2001" in {
      val priceGetter = system.actorOf(Props[SharePriceGetter])
      priceGetter ! RequestStockPrice("lloy", LocalDate.of(2001, 7, 10), LocalDate.of(2001, 7, 13))

      expectMsg(StockDataResponse("lloy",
        TreeMap[LocalDate, Double](
          LocalDate.of(2001, 7, 10) -> 0,
          LocalDate.of(2001, 7, 11) -> 10,
          LocalDate.of(2001, 7, 12) -> 20,
          LocalDate.of(2001, 7, 13) -> 30
        )))
    }

    "query lloyd share data from 10 July 2001 to 15 July 2001" in {
      val priceGetter = system.actorOf(Props[SharePriceGetter])
      priceGetter ! RequestStockPrice("lloy", LocalDate.of(2001, 7, 10), LocalDate.of(2001, 7, 15))

      expectMsg(StockDataResponse("lloy",
        TreeMap[LocalDate, Double](
          LocalDate.of(2001, 7, 10) -> 0,
          LocalDate.of(2001, 7, 11) -> 10,
          LocalDate.of(2001, 7, 12) -> 20,
          LocalDate.of(2001, 7, 13) -> 30,
          LocalDate.of(2001, 7, 14) -> 40,
          LocalDate.of(2001, 7, 15) -> 50,
        )))
    }

    "query capita share data from 10 July 2002 to 11 July 2002, it should be persisted" in {
      val priceGetter = system.actorOf(Props[SharePriceGetter])
      priceGetter ! RequestStockPrice("capita", LocalDate.of(2002, 7, 10), LocalDate.of(2002, 7, 11))

      expectMsg(StockDataResponse("capita",
        TreeMap[LocalDate, Double](
          LocalDate.of(2002, 7, 10) -> 0,
          LocalDate.of(2002, 7, 11) -> 10
        )))
    }
  }
}
