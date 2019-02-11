import java.time.LocalDate
import java.time.chrono.ChronoLocalDate

import QDecisionPolicyActor.{SelectionAction, Sell}
import SharePriceGetter.StockDataResponse
import TrainerRouterActor._
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.routing.{GetRoutees, Routee}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, Matchers, OneInstancePerTest, WordSpecLike}

import scala.collection.immutable.TreeMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class TrainerRouterActorSpec extends TestKit(ActorSystem("TrainerRouterActorSpec"))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll with OneInstancePerTest {

  implicit val ex: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(20 seconds)
  implicit val localDateOrdering: Ordering[LocalDate] = Ordering.by(identity[ChronoLocalDate])

  private val sharePrices = (55 until 255).map(i => LocalDate.of(2001, 7, 10).plusDays(i) -> i.toDouble)
  private val stockData = StockDataResponse("my-share", TreeMap(sharePrices.toArray: _*))

  private val policyProbe = TestProbe()
  private val sa = SelectionAction(_, _)
  private val future = policyProbe.ref ? sa
  policyProbe.expectMsg(0 millis, sa)
  policyProbe.reply(Sell)
  assert(future.isCompleted && future.value == Some(Success(Sell)))

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "Trainer Router actor" should {
    "create 10 children" in {
      val router = createRouterRef()
      router ! GetRoutees
      expectMsgType[Seq[Routee]].size should be(10)
    }

    "return NoTrainingDataReceived at initial stage when it receives various messages" in {
      val router = createRouterRef()
      router ! GetStd
      expectMsg(NoTrainingDataReceived)
      router ! GetAvg
      expectMsg(NoTrainingDataReceived)
      router ! StartTraining
      expectMsg(NoTrainingDataReceived)
    }

    "return NotComputed in training stage when it receives GetStd and GetAvg messages" in {
      val router = createRouterRef()
      router ! SendTrainingData(stockData)
      router ! GetAvg
      expectMsg(NotComputed)
      router ! GetStd
      expectMsg(NotComputed)
    }

    "return average and std values after every children actor is trained" in {
      val router = createRouterRef()
      router ! GetRoutees
      expectMsgType[Seq[Routee]].size should be(10)

      router ! SendTrainingData(stockData)
      router ! StartTraining

      awaitAssert({router ! GetRoutees; expectMsgType[Seq[Routee]].size should be(0)}, 20 seconds, 1 seconds)

      router ! GetAvg
      expectMsg[Double](10.0)
      router ! GetStd
      expectMsg[Double](0.0)
    }

    "return average and std values while more than a child actor is trained when it receives GetAvg message" in {
      val router = createRouterRef()
      router ! SendTrainingData(stockData)
      router ! StartTraining

      awaitAssert({
        router ! GetRoutees
        expectMsgType[Seq[Routee]].size should (be > 0 and be < 10)
      }, 3 seconds, 10 microsecond)

      router ! GetAvg
      expectMsg[Double](10.0)
      router ! GetStd
      expectMsg[Double](0.0)
    }

    "create a new child actor when it dies and do nothing when the router is in training stage" in {
      val router = createRouterRef()
      router ! SendTrainingData(stockData)
      val thirdRoutee: Routee = killThirdRoutee(router)

      thirdRouteeShouldBeDeletedAndNewOneShouldBeCreated(router, thirdRoutee)
      router ! GetAvg
      expectMsg(NotComputed)
    }

    "create a new child actor when it dies and should send a StartTraining message when the router is in trained stage" in {
      val router = createRouterRef()
      router ! SendTrainingData(stockData)
      router ! StartTraining
      val thirdRoutee: Routee = killThirdRoutee(router)

      thirdRouteeShouldBeDeletedAndNewOneShouldBeCreated(router, thirdRoutee)
      awaitAssert({router ! GetAvg; expectMsg[Double](10.0)})
    }
  }

  private def killThirdRoutee(router: ActorRef) =
    awaitAssert({
      router ! GetRoutees
      val thirdRouteeOpt = expectMsgType[Seq[Routee]].lift(3)
      thirdRouteeOpt.isDefined shouldBe true
      val thirdRoutee = thirdRouteeOpt.get
      thirdRoutee.send(PoisonPill, self)
      thirdRoutee
    }, 5 seconds, 10 millisecond)


  private def thirdRouteeShouldBeDeletedAndNewOneShouldBeCreated(router: ActorRef, thirdRoutee: Routee) =
    awaitAssert({
      router ! GetRoutees
      val routees = expectMsgType[Seq[Routee]]
      routees shouldNot contain(thirdRoutee)
      routees.size shouldBe 10
    }, 5 seconds, 10 millisecond)

  private def createRouterRef() =
    system.actorOf(Props(new TrainerRouterActor(policyProbe.ref, 2000, 0) {
      override lazy val childTrainerProp: Props = Props(new TrainerChildActor(policyProbe.ref, 2000, 0) {
        override def train(stockData: StockDataResponse): Future[Double] = Future {
          val r = new scala.util.Random()
          Thread.sleep(r.nextInt(500).abs.toLong)
          10.0
        } (ex)
      })
    }), "trainer-router-test-actor")

}