package rwrf

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

/**
  * Created by raulraja on 9/6/16.
  */
object utils {

  implicit class FutureAwaitOps[A](f: Future[A]) {
    def await: A = Await.result(f, Duration.Inf)
  }

}
