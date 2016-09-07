import monix.eval.Task
import rwrf.utils._

import scala.util.Try

object FutureServices {

  def apply() = {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.Future

    val services = new ServicesCapture[Future]

    import services._

    services.fetchUser(1L).await
  }

}

object TaskServices {

  def apply() = {
    import monix.execution.Scheduler.Implicits.global
    val servicesTask = new ServicesCapture[Task]
    import servicesTask._

    servicesTask.fetchUser(1L).coeval.value
  }

}

object TryServices {

  def apply() = {
    val servicesTry = new ServicesCapture[Try]
    import servicesTry._

    servicesTry.fetchUser(1L)
  }

}

FutureServices()
TaskServices()
TryServices()