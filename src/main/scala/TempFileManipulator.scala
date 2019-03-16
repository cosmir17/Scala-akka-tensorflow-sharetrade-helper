import java.time.DayOfWeek

import TempFileManipulator.birthDay

object TempFileManipulator extends App {
  import scala.io.Source

  val filename = "MSFT-stock-prices.txt"

  import java.time.LocalDate
  import java.time.Month

  val rawLines = Source.fromResource(filename).getLines.toSeq
  val birthDay = LocalDate.of(1992, Month.JULY, 22)

  def recFindNextWeekDay(idx: Int, birthDayInput: LocalDate): Int = {
    val day = birthDayInput.plusDays(idx)
    if(day.getDayOfWeek == DayOfWeek.SATURDAY || day.getDayOfWeek == DayOfWeek.SUNDAY) {
      val incremented = idx + 1
      recFindNextWeekDay(incremented, day)
    }
    else {
      idx + 1
    }
  }

  var dateTimeDaysExcludingWeekends = rawLines.indices.foldLeft[Seq[Int]](Seq(0))((seedFold, _) => {
    val days = recFindNextWeekDay(seedFold.last, birthDay)
    seedFold :+ days
  })

  val zippedList = rawLines.zip(dateTimeDaysExcludingWeekends)
  zippedList.foreach(println)

  val newLines =
    for (line <- zippedList)
    yield line._1 + s", ${birthDay.plusDays(line._2)}"

  import java.nio.file.Files;
  import java.nio.file.Paths
  val content = newLines.mkString("\n").getBytes
  Files.write(Paths.get("src/main/resources/MSFT-stock-prices-revised.txt"), content)
}

//prices = get_prices('MSFT', '1992-07-22', '2016-07-22')
