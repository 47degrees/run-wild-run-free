autoscale: true
build-lists: true
slidenumbers: true
---

# Run Wild, Run Free! #


(A team's journey over Scala's FP emerging patterns)

~

@raulraja CTO @47deg

---

## What is this about? ##

- Code samples illustrating common issues folks run into with Scala.

Compiled since 2013 with about ~10 teams with 5 - 6  devs per team 
+ many conf conversations.

---

## What is this about? ##

A code centric report on: 

- What a few modern Scala based code bases may look like in the wild since 2013.
- How some of those codebases may progress toward a more FP style.
- Hurdles & caveats Scala devs & FP newcomers face when attempting the above.
- Emerging FP Patterns.
- An opening discussion of what we, as a community, can do to make newcomers life easier.

---

## DISCLAIMER: What it's NOT about! ##

- A critique of [your server as a function](https://monkey.org/~marius/funsrv.pdf)
- A critique to [scala.concurrent.Future] or any other `Future` like impl.

---

## Your server as a function ##

```tut:silent
import rwrf.utils._
import scala.concurrent.Future

type Service[Req, Rep] = Req => Future[Rep]
```

A lot of current architectures are based on variations of this.

---

## Let-s build something 

### Model

```tut:silent
type UserId = Long
type AddressId = Long
type PostalCodeId = Long
type RegionId = Long
type CountryId = Long

case class User(userId: UserId, addressId: AddressId)
case class Address(addressId: AddressId, postalCodeId : PostalCodeId)
case class PostalCode(postalCodeId : PostalCodeId, regionId : RegionId)
case class Region(regionId : RegionId, countryId : CountryId)
case class Country(countryId: CountryId)
```

---

### Exceptions

Purposely unsealed to resemble most exceptions in the `Throwable` hierarchy

```tut:silent
case class NotFound(msg : String) extends RuntimeException(msg)
case class DuplicateFound(msg : String) extends RuntimeException(msg)
case class TimeoutException(msg : String) extends RuntimeException(msg)
case class HostNotFoundException(msg : String) extends RuntimeException(msg)
```

---

### DB backend

```tut:silent
def fetchRemoteUser(userId: UserId) : User =
    throw NotFound(s"user not found with id : $userId")

def fetchRemoteAddress(addressId: AddressId) : Address =
    throw DuplicateFound(s"address duplicate found")
```

---

### Services

Here is where people start tripping

```tut:silent:fail
val fetchUser: Service[UserId, User] =
    (userId: UserId) => Future {
        fetchRemoteUser(userId)
    }
```

---

### Services

Let's try again

```tut:silent
import scala.concurrent.ExecutionContext.Implicits.global

val fetchUser: Service[UserId, User] =
    (userId: UserId) => Future {
        fetchRemoteUser(userId)
    }

val fetchAddress: Service[AddressId, Address] =
    (addressId: AddressId) => Future {
        fetchRemoteAddress(addressId)
    }

val fetchUserInfo: Service[UserId, (User, Address)] =
    (userId: UserId) =>
        for {
          user <- fetchUser(userId)
          address <- fetchAddress(user.addressId)
        } yield (user, address)
```

---

### Error handling
  
What if something goes wrong?

```tut:silent
val fetchUserInfo: Service[UserId, (User, Address)] =
    (userId: UserId) =>
        for {
          user <- fetchUser(userId) //not found
          address <- fetchAddress(user.addressId) //duplicate found
        } yield (user, address)
```

---

### Error handling

At this point folks branch out and they either

- Attempt to `Future#recover` for both known and unforeseen exceptions.
- Model known exceptional cases via nested `Option`, `Either` or `Try`.

---

### Error handling

Those who attempt to recover may succeed

```tut:silent
fetchUserInfo(1L) recover {
  case _ : NotFound => User(1L, 10L)
}
```

---

### Error handling

But if you don't know the impl details and attempt to recover

```tut:silent
fetchAddress(1L) recover {
  case _ : NotFound => Address(1L, 1L)
}
```

---

### Error handling

But if you don't know the impl details and attempt to recover

```tut:silent
fetchAddress(1L) recover {
  case _ : NotFound => Address(1L, 1L)
  case _ : DuplicateFound => Address(1L, 1L)
}
```

---

### Error handling

What starts as a trivial piece of code quickly becomes

```tut:silent
import scala.util.control._

fetchAddress(1L) recover {
  case _ : NotFound => ???
  case _ : DuplicateFound => ???
  case _ : TimeoutException => ???
  case _ : HostNotFoundException => ???
  case NonFatal(e) => ???
}
```

---

### Error handling

With most `Future` based apps I've seen...

- `Http 500` production errors due to uncaught exceptions
- `Future#recover` is abused for both known/unforeseen exceptions
- Partial Functions + Unsealed hierarchies = Late night fun!

---

### Error handling

Let's mock the DB again

```tut:silent
val existingUserId = 1L
val nonExistingUserId = 2L

def fetchRemoteUser(userId: UserId) : User =
      if (userId == existingUserId) User(userId, 10L) else null

def fetchRemoteAddress(addressId: AddressId) : Address = Address(addressId, 20L)
def fetchRemotePostalCode(postalCodeId: PostalCodeId) : PostalCode = PostalCode(postalCodeId, 30L)
def fetchRemoteRegion(regionId: RegionId) : Region = Region(regionId, 40L)
def fetchRemoteCountry(countryId: CountryId) : Country = Country(countryId)
```

---

### Error handling

Folks realize they need wrap unprincipled APIs because of "Peace of mind"

```tut:silent
val fetchUser: Service[UserId, Option[User]] =
    (userId: UserId) => Future {
        Option(fetchRemoteUser(userId))
    }

val fetchAddress: Service[AddressId, Option[Address]] =
    (addressId: AddressId) => Future {
        Option(fetchRemoteAddress(addressId))
    }

val fetchPostalCode: Service[PostalCodeId, Option[PostalCode]] =
    (postalCodeId: PostalCodeId) => Future {
        Option(fetchRemotePostalCode(postalCodeId))
    }

val fetchRegion: Service[RegionId, Option[Region]] =
    (regionId: RegionId) => Future {
        Option(fetchRemoteRegion(regionId))
    }

val fetchCountry: Service[CountryId, Option[Country]] =
    (countryId: CountryId) => Future {
        Option(fetchRemoteCountry(countryId))
    }
```

---

### Error handling

The issue of having a nested type quickly surfaces

```tut:silent:fail
val fetchUserInfo: Service[UserId, (User, Address)] =
    (userId: UserId) =>
        for {
            user <- fetchUser(userId)
            address <- fetchAddress(user.addressId)
        } yield (user, address)
```

---

### Error handling

Most folks wrestle with the for comprehension but end up doing:

```tut:silent
val fetchUserInfo: Service[UserId, Option[(User, Address)]] =
    (userId: UserId) =>
        fetchUser(userId) flatMap {
            case Some(user) => fetchAddress(user.addressId) flatMap {
                case Some(address) => Future.successful(Some((user, address)))
                case None => Future.successful(None)
            }
            case None => Future.successful(None)
        }
```

---

### Error handling

PM hands out new requirements...
We need ALL THE INFO for a `User` in that endpoint! :O

---

### Error handling

As new requirements are added you start seeing this pattern everywhere

```tut:silent
val fetchUserInfo: Service[UserId, Option[(User, Address, PostalCode, Region, Country)]] =
    (userId: UserId) =>
      fetchUser(userId) flatMap {
        case Some(user) => fetchAddress(user.addressId) flatMap {
          case Some(address) => fetchPostalCode(address.postalCodeId) flatMap {
            case Some(postalCode) => fetchRegion(postalCode.regionId) flatMap {
              case Some(region) => fetchCountry(region.countryId) flatMap {
                case Some(country) =>
                  Future.successful(Some((user, address, postalCode, region, country)))
                case None => Future.successful(None)
              }
              case None => Future.successful(None)
            }
            case None => Future.successful(None)
          }
          case None => Future.successful(None)
        }
        case None => Future.successful(None)
      }
```

---

### Error handling

Code starts looking like

```tut:silent
<img src="../../../../../../../../../../../../../../../../../assets/logo.png" />
```

---

### Error handling

Code starts looking like

```tut:fail:silent
If optCashReportDay.Value = True Then 
  DoCashReportDay 
Else 
  If optCashReportWeek.Value = True Then 
    DoCashReportWeek 
  Else 
    If optCashReportMonth.Value = True Then 
      DoCashReportMonth 
    Else 
      If optCashReportAnnual.Value = True Then 
        DoCashReportAnnual 
      Else 
        If optBondReportDay.Value = True Then 
          DoBondReportDay 
          // Goes on forever....
```

---

### Error handling

At this point choose your own adventure:

- It compiles and runs, I don't care! (╯°□°）╯︵ ┻━┻
- We should ask for help, things are getting out of control! 
- FP folks (if any around) suggest they use `Monad Transformers` ☠

---

### Error handling

`OptionT`, `EitherT`, `Validated` some of the reasons folks get 
interested in FP in Scala.

---

### Error handling

For the lucky ones your code starts looking nice again

```tut:silent
import cats.data.OptionT
import cats.instances.future._

val fetchUserInfo: Service[UserId, Option[(User, Address, PostalCode, Region, Country)]] =
    (userId: UserId) => {
      val resT = for {
        user <- OptionT(fetchUser(userId))
        address <- OptionT(fetchAddress(user.addressId))
        postalCode <- OptionT(fetchPostalCode(address.postalCodeId))
        region <- OptionT(fetchRegion(postalCode.regionId))
        country <- OptionT(fetchCountry(region.countryId))
      } yield (user, address, postalCode, region, country)
      resT.value
    }
    
fetchUserInfo(existingUserId).await

fetchUserInfo(nonExistingUserId).await
```

---

### Error handling

As they digg more in FP land they learn about the importance of 
- Algebraic design through ADTs and sealed hierarchies!

```tut:silent
sealed abstract class AppException(msg : String) extends Product with Serializable
final case class NotFound(msg : String) extends AppException(msg)
final case class DuplicateFound(msg : String) extends AppException(msg)
final case class TimeoutException(msg : String) extends AppException(msg)
final case class HostNotFoundException(msg : String) extends AppException(msg)
```

---

### Error handling

And since `Option` is not expressive enough for some cases:

- Known exceptional cases start surfacing and showing up in return types.

```tut:silent
import cats.data.Xor
import cats.syntax.xor._

val fetchUser: Service[UserId, NotFound Xor User] =
    (userId: UserId) => Future {
        Option(fetchRemoteUser(userId))
            .fold(NotFound(s"User $userId not found").left[User])(x => x.right[NotFound])
    }

val fetchAddress: Service[AddressId, NotFound Xor Address] =
    (addressId: AddressId) => Future {
        Option(fetchRemoteAddress(addressId))
            .fold(NotFound(s"Address $addressId not found").left[Address])(x => x.right[NotFound])
    }

val fetchPostalCode: Service[PostalCodeId, NotFound Xor PostalCode] =
    (postalCodeId: PostalCodeId) => Future {
        Option(fetchRemotePostalCode(postalCodeId))
            .fold(NotFound(s"PostalCode $postalCodeId not found").left[PostalCode])(x => x.right[NotFound])
    }

val fetchRegion: Service[RegionId, NotFound Xor Region] =
    (regionId: RegionId) => Future {
        Option(fetchRemoteRegion(regionId))
            .fold(NotFound(s"Region $regionId not found").left[Region])(x => x.right[NotFound])
    }

val fetchCountry: Service[CountryId, NotFound Xor Country] =
    (countryId: CountryId) => Future {
        Option(fetchRemoteCountry(countryId))
            .fold(NotFound(s"Country $countryId not found").left[Country])(x => x.right[NotFound])
    }
```

---

### Error handling

They grow beyond `OptionT` and start other transformers.

```tut:silent
import cats.data.XorT

val fetchUserInfo: Service[UserId, NotFound Xor (User, Address, PostalCode, Region, Country)] =
    (userId: UserId) => {
      val resT = for {
        user <- XorT(fetchUser(userId))
        address <- XorT(fetchAddress(user.addressId))
        postalCode <- XorT(fetchPostalCode(address.postalCodeId))
        region <- XorT(fetchRegion(postalCode.regionId))
        country <- XorT(fetchCountry(region.countryId))
      } yield (user, address, postalCode, region, country)
      resT.value
    }
    
fetchUserInfo(existingUserId).await
fetchUserInfo(nonExistingUserId).await
```

---

### Non determinism

```tut:silent
import org.scalacheck._
import org.scalacheck.Prop.{forAll, BooleanOperators}

def sideEffect(latency: Int): Future[Long] = 
    Future { Thread.sleep(latency); System.currentTimeMillis }
    
def latencyGen: Gen[(Int, Int)] = for {
    a <- Gen.choose(10, 100)
    b <- Gen.choose(10, 100)
} yield (a, b)

val test = forAll(latencyGen) { latency =>
    val ops = for {
        a <- sideEffect(latency._1)
        b <- sideEffect(latency._2)
    } yield (a, b)
    val (read, write) = ops.await
    read < write
}
```

---

### Non determinism

```tut:silent
val test = forAll(latencyGen) { latency =>
    val op1 = sideEffect(latency._1)
    val op2 = sideEffect(latency._2)
    val ops = for {
        a <- op1
        b <- op2
    } yield (a, b)
    val (read, write) = ops.await
    read < write
}
```

---

### What others things are folks reporting?

- Wrong order of Effects (When someone recommends moving )
- Random deadlocks 
  (Custom ExecutionContexts)
- General confusion as to why most combinators require an implicit EC
  when one was already provided to `Future#apply`.
- A lot of reusable code becomes non reusable because it's inside a `Future`

---

### Code reuse?

Can we make our code available to other runtimes beside `Future`.  

---

### Abstracting over the return type

Our services are coupled to `Future`

```scala
type Service[A, B] = A => Future[B]
```

---

### Abstracting over the return type

But they don't have to 

```tut:silent
type Service[F[_], A, B] = A => F[B]
```

---

### Abstracting over the return type

But they don't have to 

```tut:silent
type Service[F[_], A, B] = A => F[B]
```

---

### Abstracting over the return type

We just need a way to lift a `thunk: => A` to an `F[_]`
 
```tut:silent
import simulacrum.typeclass

@typeclass trait Capture[F[_]] {
  def capture[A](a: => A): F[A]
}
```

---

### Abstracting over the return type

We'll add one instance per type we are wanting to support

```tut:silent
import scala.concurrent.ExecutionContext

implicit def futureCapture(implicit ec : ExecutionContext) : Capture[Future] = 
    new Capture[Future] {
        override def capture[A](a: => A): Future[A] = Future(a)(ec)
    }
    
import monix.eval.Task

implicit val taskCapture : Capture[Task] = 
    new Capture[Task] {
        override def capture[A](a: => A): Task[A] = Task.evalOnce(a)
    }
    
import scala.util.Try

implicit val tryCapture : Capture[Try] = 
    new Capture[Try] {
        override def capture[A](a: => A): Try[A] = Try(a)
    }
```

---

### Abstracting over the return type

Our services now are parametrized to any `M[_]` for which a `Capture` 
instance is found.

```tut:silent
class Services[F[_] : Capture] {

    val fetchUser: Service[F, UserId, NotFound Xor User] =
      (userId: UserId) => Capture[F].capture {
        Option(fetchRemoteUser(userId))
          .fold(NotFound(s"User $userId not found").left[User])(x => x.right[NotFound])
      }

    val fetchAddress: Service[F, AddressId, NotFound Xor Address] =
      (addressId: AddressId) => Capture[F].capture {
        Option(fetchRemoteAddress(addressId))
          .fold(NotFound(s"Address $addressId not found").left[Address])(x => x.right[NotFound])
      }

    val fetchPostalCode: Service[F, PostalCodeId, NotFound Xor PostalCode] =
      (postalCodeId: PostalCodeId) => Capture[F].capture {
        Option(fetchRemotePostalCode(postalCodeId))
          .fold(NotFound(s"PostalCode $postalCodeId not found").left[PostalCode])(x => x.right[NotFound])
      }

    val fetchRegion: Service[F, RegionId, NotFound Xor Region] =
      (regionId: RegionId) => Capture[F].capture {
        Option(fetchRemoteRegion(regionId))
          .fold(NotFound(s"Region $regionId not found").left[Region])(x => x.right[NotFound])
      }

    val fetchCountry: Service[F, CountryId, NotFound Xor Country] =
      (countryId: CountryId) => Capture[F].capture {
        Option(fetchRemoteCountry(countryId))
          .fold(NotFound(s"Country $countryId not found").left[Country])(x => x.right[NotFound])
      }

}

object Services {
    def apply[F[_] : Capture] : Services[F] = new Services[F]
    
    implicit def instance[F[_]: Capture]: Services[F] = apply[F]
}
```

---

### Abstracting over the return type

Code becomes reusable regardless of the target runtime

```tut:silent
Services[Future].fetchUser(existingUserId)

Services[Task].fetchUser(existingUserId)

Services[Try].fetchUser(existingUserId)
```

---

### Abstracting over implementations

Now that we can run our code to any `F[_]` that can 
capture a lazy computation we may want to abstract over 
implementations too.

---

### Abstracting over implementations

Free Monads / Applicatives is what has worked best for us.
- Free of interpretation allowing multiple runtimes.
- Supports abstracting over return types.
- Getting momentum with multiple posts and libs supporting the pattern.
 (List those here)

---

### Abstracting over implementations

Let's refactor our services to run on `Free`?

---

### Abstracting over implementations

Define your Algebra

```tut:silent
sealed abstract class ServiceOp[A] extends Product with Serializable
final case class FetchUser(userId: UserId) extends ServiceOp[NotFound Xor User]
final case class FetchAddress(addressId: AddressId) extends ServiceOp[NotFound Xor Address]
final case class FetchPostalCode(postalCodeId: PostalCodeId) extends ServiceOp[NotFound Xor PostalCode]
final case class FetchRegion(regionId: RegionId) extends ServiceOp[NotFound Xor Region]
final case class FetchCountry(countryId: CountryId) extends ServiceOp[NotFound Xor Country]
```

---

### Abstracting over implementations

Lift your Algebra to Free

```tut:silent
import cats.free.Free

type ServiceIO[A] = Free[ServiceOp, A]

object ServiceOps {
    def fetchUser(userId: UserId): ServiceIO[NotFound Xor User] = 
        Free.liftF(FetchUser(userId))
    def fetchAddress(addressId: AddressId): ServiceIO[NotFound Xor Address] = 
        Free.liftF(FetchAddress(addressId))
    def fetchPostalCode(postalCodeId: PostalCodeId): ServiceIO[NotFound Xor PostalCode] = 
        Free.liftF(FetchPostalCode(postalCodeId))
    def fetchRegion(regionId: RegionId): ServiceIO[NotFound Xor Region] = 
        Free.liftF(FetchRegion(regionId))
    def fetchCountry(countryId: CountryId): ServiceIO[NotFound Xor Country] = 
        Free.liftF(FetchCountry(countryId))
}
```

---

### Abstracting over implementations

Write 1 or many interpreters that may be swapped at runtime

```tut:silent
def interpreter[M[_] : Capture : Monad : RecursiveTailRecM]
    (implicit impl: Services[M]): ServiceOp ~> M = 
        new (ServiceOp ~> M) {
            override def apply[A](fa: ServiceOp[A]): M[A] = {
            val result = fa match {
                case FetchUser(userId) => impl.fetchUser(userId)
                case FetchAddress(addressId) => impl.fetchAddress(addressId)
                case FetchPostalCode(postalCodeId) => impl.fetchPostalCode(postalCodeId)
                case FetchRegion(regionId) => impl.fetchRegion(regionId)
                case FetchCountry(countryId) => impl.fetchCountry(countryId)
            }
            result.asInstanceOf[M[A]]
        }
```

---

### Abstracting over implementations

Write programs using the smart constructors and combining them at will

```tut:silent
import ServiceOps._

def fetchUserInfo(userId: UserId): ServiceIO[NotFound Xor (User, Address, PostalCode, Region, Country)] = {
    val resT = for {
        user <- XorT(fetchUser(userId))
        address <- XorT(fetchAddress(user.addressId))
        postalCode <- XorT(fetchPostalCode(address.postalCodeId))
        region <- XorT(fetchRegion(postalCode.regionId))
        country <- XorT(fetchCountry(region.countryId))
    } yield (user, address, postalCode, region, country)
    resT.value
}
```

---

### Abstracting over implementations

Run your programs to any target implementation and runtime

```tut:silent
val tryResult = fetchUserInfo(existingUserId).foldMap(interpreter[Try])

val taskResult = fetchUserInfo(existingUserId).foldMap(interpreter[Task])

val futureResult = fetchUserInfo(existingUserId).foldMap(interpreter[Future])
```

---

### Patterns

Recommendations for others that have worked for us:

- Algebraic design and sealed hierarchies for safer exceptions control. 
  (Don't match and Guess).
- Abstract over return types for code reuse.
- Abstract over implementations to increase flexibility and composition.

---

### Conclusion

- Most Scala newcomers are not exposed to Typed FP when they start.
- There a repeating patterns .
- Scala is not a Functional Programming Language.

---