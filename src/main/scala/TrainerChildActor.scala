import QDecisionPolicyActor._
import SharePriceGetter.StockDataResponse
import TrainerChildActor._
import TrainerRouterActor._
import akka.actor.{ActorRef, FSM}
import akka.pattern.{ask, pipe}
import org.platanios.tensorflow.api.Tensor

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object TrainerChildActor {
  case object GetPortfolio
  case object Initialise
  case object Initialised

  case class Train(stockData: StockDataResponse)

  type UpdatedBudgetNoOfStocksAction = (Double, Int, Action)
  type RewardAndNewState = (Double, Tensor[Float])
  type UpdatedBudgetNoOfStocksShareValue = (Double, Int, Double)
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
    case Event(Initialise, NotComputed) =>
      println(s"${context.self.path.name} is already in ready state")
      stay()
  }

  when(Trained) {
    case Event(GetPortfolio, t@TrainingData(_)) =>
      sender() ! t
      stay()
    case Event(Initialise, _) =>
      context.parent ! Initialised
      goto(Ready) using NotComputed
  }

  initialize()

  def train(stockData: StockDataResponse): Future[Double] = stockData match {
    case StockDataResponse(_, sharePrices) if sharePrices.size > QDecisionPolicyActor.h1Dim + 1 =>
      val pricesIndexed = sharePrices.toIndexedSeq.map(_._2.toFloat)
      val budgetNoOfStockShareVFolded = computeWithFolding(QDecisionPolicyActor.h1Dim, sharePrices.size - QDecisionPolicyActor.h1Dim - 1, pricesIndexed, sender())
      budgetNoOfStockShareVFolded.map(tuple => tuple._1 + tuple._2 * tuple._3)
    case StockDataResponse(_, _) =>
      throw new IllegalArgumentException("Stock price count should be more than Tensorflow input nodes")
  }

  /**
    * computing the final budget and stock shares (and handing over share value to next iteration)
    * updated values(budget, no of stock shares and share value) should be passed on to next iteration.
    *
    * @param historyDim
    * @param priceCountsExcludingH1Dim
    * @param pricesIndexed
    * @return (1 updated budget, 2 no of stock shares, 3 share value) tuple
    */
  private def computeWithFolding(historyDim: Int, priceCountsExcludingH1Dim: Int, pricesIndexed: IndexedSeq[Float], origSender: ActorRef)
  : Future[UpdatedBudgetNoOfStocksShareValue] =
    (0 until priceCountsExcludingH1Dim).foldLeft[Future[UpdatedBudgetNoOfStocksShareValue]](Future {
      (myBudget, noOfStocks, 0.0)
    }) { //seed is budget, noStock, shareValue
      (budgetNoOfStockShareValueTupleFuture, i) =>
        println(s"progress ${100 * i / (pricesIndexed.size - historyDim - 1)}%")
        val currentState: Future[Tensor[Float]] =
          budgetNoOfStockShareValueTupleFuture.map(budgetStocks => pricesIndexed.slice(i, i+historyDim + 1) ++ Seq(budgetStocks._1.toFloat, budgetStocks._2.toFloat))
        val currentPortfolio = budgetNoOfStockShareValueTupleFuture.map(tuple => tuple._1 + tuple._2 * tuple._3)
        val actionFuture = currentState.map(cs => SelectionAction(cs, i.toFloat)).flatMap(m => policyActor.ask(m)(timeout, origSender).mapTo[Action])
        val newShareValue: Double = pricesIndexed(i + historyDim + 1)
        val newBudgetNoOfStockAction = makeDecisionAccordingToAction(actionFuture, newShareValue)
        val rewardAndNewStateTuple = extractRewardAndNewState(i, currentPortfolio, newShareValue, pricesIndexed, historyDim, newBudgetNoOfStockAction)

        createUpdateQ(currentState, rewardAndNewStateTuple).pipeTo(policyActor)(origSender)
        newBudgetNoOfStockAction.map(tuple => (tuple._1, tuple._2, newShareValue))
    }

  /**
    * making decision of buying and selling shares or hold current position
    *
    * @param actionFuture Sell, Buy, Hold action wrapped with future
    * @param newShareValue share price
    * @return (1 updated budget, 2 no of stock shares, 3 action) tuple
    */
  private def makeDecisionAccordingToAction(actionFuture: Future[Action], newShareValue: Double): Future[UpdatedBudgetNoOfStocksAction] =
    actionFuture.map {
      case a@Buy if myBudget >= newShareValue => (myBudget - newShareValue, noOfStocks + 1, a)
      case a@Sell if noOfStocks > 0 => (myBudget + newShareValue, noOfStocks - 1, a)
      case _ => (myBudget, noOfStocks, Hold)
    }

  /**
    * self explanatory
    *
    * @param i iteration value from the folding method 'computeWithFolding'
    * @param currentPortfolio currentPortfolio value
    * @param newShareValue
    * @param pricesIndexed
    * @param historyDim
    * @param newBudgetNoOfStockAction
    * @return (reward, newState) tuple wrapped with future
    */
  private def extractRewardAndNewState(i: Int, currentPortfolio: Future[Double], newShareValue: Double, pricesIndexed: IndexedSeq[Float],
                                       historyDim: Int, newBudgetNoOfStockAction: Future[UpdatedBudgetNoOfStocksAction]): Future[RewardAndNewState] =
    for {
      currentPortfolio <- currentPortfolio
      newBudgetNoOfStockAction <- newBudgetNoOfStockAction
    } yield {
      val newPortfolio = newBudgetNoOfStockAction._1 + newBudgetNoOfStockAction._2 * newShareValue
      val reward = newPortfolio - currentPortfolio
      val newState: Tensor[Float] = pricesIndexed.slice(i + 1, i + historyDim + 2) ++ Seq(newBudgetNoOfStockAction._1.toFloat, newBudgetNoOfStockAction._2.toFloat)
      (reward, newState)
    }

  /**
    * create UpdateQ case class
    * @param currentState
    * @param rewardAndNewState
    * @return UpdateQ case class containing 'current state', 'reward' and 'new state'
    */
  private def createUpdateQ(currentState: Future[Tensor[Float]], rewardAndNewState: Future[RewardAndNewState]): Future[UpdateQ] =
    for { //for Future
      cState <- currentState
      rewardAndNewStateTuple <- rewardAndNewState
    } yield UpdateQ(cState, rewardAndNewStateTuple._1.toFloat, rewardAndNewStateTuple._2)
}


