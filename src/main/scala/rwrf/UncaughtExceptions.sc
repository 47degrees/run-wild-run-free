import rwrf.utils.ServicesVanilla._
import rwrf.utils._

import scala.concurrent.ExecutionContext.Implicits.global

// uncaught exception because partial match guessing and
// the Throwable hierarchy is unsealed
(fetchAddress(1L) recover {
  case e : NotFound => println("Caught! exception dealt with!")
}).await
