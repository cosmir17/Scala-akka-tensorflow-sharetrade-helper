import java.time.LocalDate

import QDecisionPolicyActor.{SelectionAction, Sell}
import SharePriceGetter.StockDataResponse
import TrainerRouterActor._
import akka.actor.{ActorSystem, PoisonPill, Props, Terminated}
import akka.pattern.ask
import akka.routing.Routees
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.immutable.TreeMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

class TrainerRouterActorSpec extends TestKit(ActorSystem("TrainerRouterActorSpec"))
  with ImplicitSender with BeforeAndAfterAll with WordSpecLike with Matchers {

  implicit val ex: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(20 seconds)

  private val sharePrices = (55 until 255).map(i => LocalDate.of(2001, 7, 10).plusDays(i) -> i.toDouble)
  private val stockData = StockDataResponse("my-share", TreeMap(sharePrices.toArray: _*))

  private val policyProbe = TestProbe()
  private val sa = SelectionAction(_, _)
  private val future = policyProbe.ref ? sa
  policyProbe.expectMsg(0 millis, sa)
  policyProbe.reply(Sell)
  assert(future.isCompleted && future.value == Some(Success(Sell)))

  override protected def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "Trainer Router actor" should {
    "create 10 children" in {
      val router = createRouterRef()
      val routeesFuture = (router ? akka.routing.GetRoutees).mapTo[Routees]
      routeesFuture.map(_.getRoutees.size shouldBe 10)
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

    "expect Trained to be received in training stage when StartTraining is sent once" in {
      val router = createRouterRef()
      router ! SendTrainingData(stockData)
      router ! StartTraining
      expectMsg(Trained)
      router ! GetAvg
      expectMsg(10)
    }

    "return average and std values while more than a child actor is trained when it receives GetAvg message" in {
      val router = createRouterRef()
      router ! SendTrainingData(stockData)
      (0 until 10).foreach(_ => router ! StartTraining)
      (0 until 10).foreach(_ => expectMsg(Trained))
      router ! GetAvg
      expectMsg[Double](10)
      router ! GetStd
      expectMsg[Double](0)
    }

    "create a new child actor when it dies and do nothing when the router is in training stage" in {
      val router = createRouterRef()
      router ! SendTrainingData(stockData)
      val thirdRouteeOptionFuture = (router ? akka.routing.GetRoutees).mapTo[Routees].map(_.routees).map(_(3))
      thirdRouteeOptionFuture.map(_.send(PoisonPill, self))
      expectMsg(Terminated)
    }

    "create a new child actor when it dies and should send a StartTraining message when the router is in trained stage" in {
      val router = createRouterRef()
      router ! SendTrainingData(stockData)
      (0 until 10).foreach(_ => router ! StartTraining)
      (0 until 10).foreach(_ => expectMsg(Trained))
      val thirdRouteeOptionFuture = (router ? akka.routing.GetRoutees).mapTo[Routees].map(_.routees).map(_(3))
      thirdRouteeOptionFuture.map(_.send(PoisonPill, self))
      expectMsg(Terminated)
      expectMsg(StartTraining)
    }
  }

  private def createRouterRef() =
    system.actorOf(Props(new TrainerRouterActor(policyProbe.ref, 2000, 0) {
      override val childTrainer: TrainerChildActor = new TrainerChildActor(policyProbe.ref, 2000, 0) {
        override def train(stockData: SharePriceGetter.StockDataResponse): Future[Double] = Future {10}
      }
    }), "trainer-router-actor")
}