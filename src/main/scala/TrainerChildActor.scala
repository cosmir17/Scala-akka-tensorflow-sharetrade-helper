import QDecisionPolicyActor._
import SharePriceGetter.StockDataResponse
import TrainerChildActor.GetPortfolio
import TrainerRouterActor._
import akka.actor.{ActorRef, FSM}
import akka.pattern.pipe
import org.platanios.tensorflow.api.Tensor
import org.platanios.tensorflow.api.core.Shape
import org.platanios.tensorflow.api.tensors.ops.Basic.concatenate

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object TrainerChildActor {
  case object GetPortfolio
}

class TrainerChildActor(policyActor: ActorRef, myBudget: Double, noOfStocks: Int) extends FSM[TrainerState, TrainerData] {
  implicit val ec: ExecutionContext = context.dispatcher
  implicit val timeout: akka.util.Timeout = akka.util.Timeout(10 seconds)

  startWith(Ready, NotComputed)

  when(Ready) {
    case Event(Train(stockData), NotComputed) =>
      val portfolioFuture = train(stockData)
      portfolioFuture.map(TrainingData) pipeTo self
      stay()
    case Event(t@TrainingData(_), NotComputed) =>
      context.parent ! Trained
      goto(Trained) using t
    case Event(GetPortfolio, NotComputed) =>
      sender() ! NotComputed
      stay()
  }

  when(Trained) {
    case Event(GetPortfolio, t@TrainingData(_)) =>
      sender() ! t
      stay()
  }

  initialize()

  def train(stockData: StockDataResponse): Future[Double] = {
    val historyDim = QDecisionPolicyActor.h1Dim
    val fee = 10.14 //todo UK stamp fee
    val pricesWithoutLastHDim = stockData.sharePrices.size - historyDim
    val pricesIndexed = stockData.sharePrices.toIndexedSeq.map(_._2.toFloat)
    val budgetNoOfStockShareVFolded = computeWithFolding(historyDim, pricesWithoutLastHDim, pricesIndexed)

    budgetNoOfStockShareVFolded.map(tuple => tuple._1 + tuple._2 * tuple._3)
  }

  /**
    * computing the final budget and stock shares (and handing over share value to next iteration)
    * updated values(budget, no of stock shares and share value) should be passed on to next iteration.
    * @param historyDim
    * @param pricesWithoutLastHDim
    * @param pricesIndexed
    * @return (1 updated budget, 2 no of stock shares, 3 share value) tuple
    */
  private def computeWithFolding(historyDim: Int, pricesWithoutLastHDim: Int, pricesIndexed: immutable.IndexedSeq[Float]): Future[(Double, Int, Double)] =
    (0 until pricesWithoutLastHDim).foldLeft[Future[(Double, Int, Double)]](Future {
      (myBudget, noOfStocks, 0.0)
    }) { //seed is budget, noStock, shareValue
      (budgetNoOfStockShareValueTupleFuture, i) =>
        println(s"progress ${100 * i / pricesIndexed.size - historyDim - 1}%")
        val priceSliced = pricesIndexed.slice(i, i + historyDim)
        val priceSlicedTensor = Tensor[Float](priceSliced).reshape(Shape(-1, priceSliced.size))
        val currentState: Future[Tensor[Float]] = budgetNoOfStockShareValueTupleFuture
          .map(budgetStocks => concatenate(Seq(priceSlicedTensor, budgetStocks._1.toFloat, budgetStocks._2.toFloat), null))
        val currentPortfolio = budgetNoOfStockShareValueTupleFuture.map(tuple => tuple._1 + tuple._2 * tuple._3)
        val actionFuture = (currentState.map(cs => SelectionAction(cs, i.toFloat)) pipeTo policyActor).mapTo[Action]
        val newShareValue: Double = pricesIndexed(i + historyDim + 1)
        val newBudgetNoOfStockAction = makeDecisionAccordingToAction(actionFuture, newShareValue)
        val rewardAndNewStateTuple = extractRewardAndNewState(i, currentPortfolio, newShareValue, pricesIndexed, historyDim, newBudgetNoOfStockAction)

        createUpdateQ(currentState, rewardAndNewStateTuple) pipeTo policyActor
        newBudgetNoOfStockAction.map(tuple => (tuple._1, tuple._2, newShareValue))
    }

  /**
    * making decision of buying and selling shares or hold current position
    * @param actionFuture Sell, Buy, Hold action wrapped with future
    * @param newShareValue share price
    * @return (1 updated budget, 2 no of stock shares, 3 action) tuple
    */
  private def makeDecisionAccordingToAction(actionFuture: Future[Action], newShareValue: Double): Future[(Double, Int, Action)] =
    actionFuture.map {
      case a@Buy if myBudget >= newShareValue => (myBudget - newShareValue, noOfStocks + 1, a)
      case a@Sell if noOfStocks > 0 => (myBudget + newShareValue, noOfStocks - 1, a)
      case _ => (myBudget, noOfStocks, Hold)
    }

  /**
    * self explanatory
    * @param i iteration value from the folding method 'computeWithFolding'
    * @param currentPortfolio currentPortfolio value
    * @param newShareValue
    * @param pricesIndexed
    * @param historyDim
    * @param newBudgetNoOfStockAction
    * @return (reward, newState) tuple wrapped with future
    */
  private def extractRewardAndNewState(i: Int, currentPortfolio: Future[Double], newShareValue: Double, pricesIndexed: IndexedSeq[Float],
                                       historyDim: Int, newBudgetNoOfStockAction: Future[(Double, Int, Action)]): Future[(Double, Tensor[Float])] =
    for {
      currentPortfolio <- currentPortfolio
      newBudgetNoOfStockAction <- newBudgetNoOfStockAction
    } yield {
      val newPortfolio = newBudgetNoOfStockAction._1 + newBudgetNoOfStockAction._2 * newShareValue
      val reward = newPortfolio - currentPortfolio
      val newPriceSliced = pricesIndexed.slice(i + 1, i + historyDim + 1)
      val newPrices = Tensor[Float](newPriceSliced).reshape(Shape(-1, newPriceSliced.size))
      val newState: Tensor[Float] = concatenate(Seq(newPrices, newBudgetNoOfStockAction._1.toFloat, newBudgetNoOfStockAction._2.toFloat), null)
      (reward, newState)
    }

  /**
    * create UpdateQ case class
    * @param currentState
    * @param rewardAndNewState
    * @return UpdateQ case class containing 'current state', 'reward' and 'new state'
    */
  private def createUpdateQ(currentState: Future[Tensor[Float]], rewardAndNewState: Future[(Double, Tensor[Float])]): Future[UpdateQ] =
    for { //for Future
      cState <- currentState
      rewardAndNewStateTuple <- rewardAndNewState
    } yield UpdateQ(cState, rewardAndNewStateTuple._1.toFloat, rewardAndNewStateTuple._2)
}



