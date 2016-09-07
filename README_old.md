# [fit] Run Wild, Run Free!

-- A team's journey over Scala's FP emerging patterns

---

# Many ways to get

- As consultants we frequently engage in multiple project with diverse teams.
- People love / hate Scala
- They love functional combinators (map, filter, flatMap, sequence, traverse...)
- Let's look at a few examples of the `scala.concurrent.Future` api.

---

# Folks are tripping on _scala.concurrent.Future_

```scala
type Service[Req, Res] = Req => Future[Res]
```
-- As seen on "your server is a function"

---

# Let us imagine a domain composed of a User and it's Address

```scala
case class User(userId: UserId, addressId: AddressId , ...)
case class Address(addressId: AddressId, ...)
```

---

# A service that fetches a User

```scala
val fetchUser : Service[UserId, User] = 
  id: UserId => Future {
    DB.fetchUser(id) 
  }   
```

---

# A service that fetches an Address

```scala
val fetchAddress : Service[AddressId, Address] = 
  id: AddressId => Future {
    DB.fetchAddress(id) 
  }   
```

---

# Let's bind them to fetch both pieces of information

```scala
val fetchUserInfoService: Service[UserId, (User, Address)] = 
  id : UserId =>  
    for {
      user <- fetchUser(id)
      address <- fetchAddress(user.addressId)
    } yield (user, address)
```

---

# But the world ain't perfect and shit happens

```scala
val fetchUserInfoService: Service[UserId, (User, Address)] = 
  id : UserId =>  
    for {
      user <- fetchUser(id) // no user? NPE
      address <- fetchAddress(user.addressId) // no address? 
    } yield (user, address)
```

---

# Future.failed!

Match and guess time...

```scala
fetchUserInfoService(userId) recover {
  case NotFound() => ...
}
```

---

# Future.failed!

BTW these are micro services so...

```scala
fetchUserInfoService(userId) recover {
  case UserNotFoundException() => ...
  case AddressNotFoundException() => ...
  case TimeoutException() => ...
  case HostNotFoundException() => ...
}
```

---

# Future.failed!

Yo! I'm still getting http 500 Errors!

```scala
fetchUserInfoService(userId) recover {
  case UserNotFoundException() => ...
  case AddressNotFoundException() => ...
  case TimeOutException() => ...
  case HostNotFoundException() => ...
  case NonFatal(e) => "sorry bro!"
}
```

---

# Future.failed!

```scala
fetchUserInfoService(userId) recover {
  case UserNotFoundException() => ...
  case AddressNotFoundException() => ...
  case TimeOutException() => ...
  case HostNotFoundException() => ...
  case NonFatal(e) => "sorry bro!"
}
```

---

# Future.failed!

We love Scala because we don't have to write `if (a != null)` anymore but we are recreating the same anti-pattern where stuff blows up at runtime for types that capture effects like `scala.concurrent.Future` just because we fail to be explicit about the errors we already know.

---

# Anti-patterns

1 - Well known untyped exceptions on unsealed hierarchies are uncaught/swallowed and raised by whatever framework you are using at runtime.

---

# Ok so let's improve error handling

```scala
val fetchUser : Service[UserId, Option[User]] = 
  id: UserId => Future {
    DB.fetchUser(id) // DB call now returns Option[User]
  }   
```

---

# Ok so let's improve error handling

```scala
val fetchAddress : Service[AddressId, Option[Address]] = 
  id: AddressId => Future {
    DB.fetchAddress(id) // DB call now returns Option[Address]
  }   
```

---

# General confusion as to how to get to the underlying `Res` in a for comprehension

```scala
val fetchUserInfoService: Service[UserId, Option[(User, Address)]] = 
  id : UserId =>  
    for {
      user <- fetchUser(id)
      address <- fetchAddress(user.addressId) //user is an Option and won't compile
    } yield (user, address)
```

---

# Giving up so back to good old callback style

```scala
replace with WS
```

---

# App requirements change and we require detailed postal, region and country info.

```scala
val fetchUserInfoService: Service[UserId, Option[(User, Address, PostalCode, Region, Country)]] = 
  id : UserId =>  
    fetchUser(id) flatMap { 
      case Some(user) => fetchAddress(user.id) {
        case Some(address) => fetchPostalCode(address.postalCodeId) { 
          case Some(postalCode) => fetchRegion(postalCode.regionId) {
            case Some(region) => fetchCountry(region.countryId) {
              case Some(country) => Future.successful(Some(user, address, postalCode, region, country))
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

# Also seen in : 

```html
<img src="../../../../../../../../../../../../../../../../../assets/logo.png" />
```

---

# Also seen in : 

```visualbasic
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

http://thedailywtf.com/articles/Innovations_from_the_Inventor_of_the__0x26_quot_0x3b_ElseIf_0x26_quot_0x3b_

---

# Vanilla Scala anti-patterns

1 - Uncaught untyped exceptions on unsealed hierarchies
2 - Arrowhead Anti Pattern

---

# Then someone looks at it and WTF! Monad Transformers FTW!

```scala
val fetchUserInfoService: Service[UserId, Option[(User, Address, PostalCode, Region, Country)]] = 
  id : UserId =>  
    val res = for {
      user <- fetchUser(id).liftT[OptionT]
      address <- fetchAddress(user.addressId).liftT[OptionT]
      postalCode <- fetchPostalCode(address.postalCodeId).liftT[OptionT]
      region <- fetchRegion(postalCode.regionId).liftT[OptionT]
      country <- fetchCountry(region.countryId).liftT[OptionT]
    } yield (user, address, postalCode, region, country)
    res.run
```

---

# Timeouts => Unpredictable build failures

---

# The wrong ExecutionContext

```scala
import scala.concurrent.ExecutionContext.global

for {
  a <- nonBlocking
  b <- simpleDbLookups   
  c <- expensiveDbLookups
  d <- dbWriteOperations
  e <- expensiveCpuOperations
} yield result
```

http://blog.jessitron.com/2014/01/choosing-executorservice.html
https://github.com/alexandru/scala-best-practices/blob/master/sections/4-concurrency-parallelism.md

https://github.com/alexandru/scala-best-practices/blob/master/sections/4-concurrency-parallelism.md

---

# In an attempt to improve performance without not understanding the issue code is parallelized causing Effects execution in the wrong order

```scala
import scala.concurrent.ExecutionContext.global

val af = fetchUserInfoService(userId)
val bf = simpleDbLookups   
val cf = expensiveDbLookups
val df = dbWriteOperations
val ef = expensiveCpuOperations

for {
  a <- af
  b <- bf   
  c <- cf
  d <- df
  e <- ef
} yield result
```

---

# At this point friend also points out that all needs to change to a proper transformer stack like:

```scala
type Service[D, L, R] = XorT[({type λ[α] = ReaderT[Task, D, α]})#λ, L, R]
``` 

---

# Because we want dependencies injected, purity, exception handling, effect capturing and composition. 

```scala
type Service[D, L, R] = XorT[ReaderT[Task, D, ?], L, R]
``` 

---

# Anti-patterns

1 - Uncaught untyped exceptions on unsealed hierarchies
2 - Flatmap Piramids
3 - Implicits abuse
4 - Complex encoding to support Typed FP

# FP Patterns

1 - Exceptions in returned types
2 - Transformers stacks

---

# Dev tries it for a while but quickly sees it's encoding limitations. All needs to get lifted to the stack increasing verbosity. Others in the team don't wanna touch the code because they are afraid or don't understand what it does. They have never been exposed to true Typed Functional Programming because Vanilla Scala does not expose you to Functional Programming beyond collection combinators.

---

# Dev wonders if he is ever gonna get this FP thing right and asks to include cats or Scalaz as a dependency. 
Manager says No! because of symbolic operators or too advanced to find new hires. 

---

Dev reinvents the wheel creating it's own ad-hoc specialized transformers and other FP type classes and unlawful datatypes derived from *Stack Overflow* including stack unsafe versions of Free, StateT and friends.

- App blows up in production with a *StackOverflow*

---

At this point some folks go out there looking for something better in FP land or stick to their good old OOP practices in Scala contributing to hybrid hard to understand codebases where ad-hoc OOP patterns sit on top of basic FP combinators

---

What alternatives are out there to solve some of this issues?

---

Abstract over return types using type classes or other libraries that help you do that.

---

# Abstract over return types

```scala
type Service[A, B] = A => Future[B] 

val fetchUser : Service[UserId, Option[User]] = 
  id: UserId => Future {
    DB.fetchUser(id) // DB call now returns Option[User]
  }   
```

---

# Abstract over return types

```scala
type Service[A, B, M[_]] = A => M[B] 

def fetchUser[M[_]: Capture] : Service[UserId, Option[User], M] = 
  id: UserId => Capture[M].apply {
    DB.fetchUser(id)
  }   
```

---

# Abstract over return types

```scala
fetchUser[Future](userId) 
fetchUser[Task](userId)
fetchUser[Id](userId)
```

---

# Abstract over return types

- You explicitly know that effects are being captured
- Code works in other contexts
- Behavior is constrained to the type class and not the full blown type api.
- You can describe your behaviors as type classes. Effect capturing, non determinism, blocking vs non-blocking, you name it behavior, etc...

---

# Model exceptions as ADTs

```scala
sealed abstract class PersistenceException
final case class NotFound(...) extends PersistenceException
final case class DuplicateFound(...) extends PersistenceException
```

---

# Make your exceptions become part of the return type

```scala
def fetchUser[M[_]: Capture] : Service[UserId, NotFound Xor User, M] = 
  id: UserId => Capture[M].apply {
    DB.fetchUser(id).fold(e => Xor.left(Notfound(e)), u => Xor.right(u))
  }   
```

---

# Make execution explicit. Users prefer control over magic

```scala
sealed abstract class PersistenceOp[A]
final case class FetchUser[UserId] extends PersistenceOp[NotFound Xor User]

object PersistenceOps {
  def fetchUser(userId : UserId) : Free[PersistenceOp, NotFound Xor User] =
  Free.liftF(FetchUser(userId))
}
```

---

# Make execution explicit. Users prefer control over magic

```scala
sealed abstract class PersistenceOp[A]
final case class FetchUser[UserId] extends PersistenceOp[NotFound Xor User]

object PersistenceOps {
  def fetchUser(userId : UserId) : Free[PersistenceOp, NotFound Xor User] =
  Free.liftF(FetchUser(userId))
}
```

---

# Make execution explicit. Users prefer control over magic

```scala
def runtime[M[_] : Capture] : PersistenceOp ~> M = 
  new (PersistenceOp ~> M) {
    def apply[A](fa : PersistenceOp[A]) : M[A] = fa match {
      case FetchUser(id) => Capture[M].apply(DB.fetchUser(id))    
      ...  
    }
  }  
```

---

# Make execution explicit. Users prefer control over magic

```scala
def runtime[M[_] : Capture] : PersistenceOp ~> M = 
  new (PersistenceOp ~> M) {
    def apply[A](fa : PersistenceOp[A]) : M[A] = fa match {
      case FetchUser(id) => Capture[M].apply(DB.fetchUser(id))    
      ...  
    }
  }  
```

---

# Free Monads and applicative

---

When someone is good at Scala and it's in a team you quickly gain control over the code base and many of the decisions in there.
Don't let that make you forget others are starting and unfamiliar with FP patterns.

---

The only way out is to make people familiar with Typed Functional Programming from the very beginning.

---

FP in Scala should not be a gradual step, it should be taught and learned along with the Language syntax and other features such as implicits, lambdas, etc.

---

```

Abstracting over return types

Abstracting over return types:

Inspiration : Jon Rapture

Dislike duplicity for interoperability.

The case of Monad Error.

The missing pureEval and the need for Capture

```

---

Lessons learned.

- Composability of despair algebras
- Always allow the user to specify it's own runtime types

---

The more generic your code is the less chances to get it wrong.

Code to abstractions were possible.

---

How can we solve this mess?

Transformers, Free are good solutions but the underlying problem is the lack of FP exposure newcomers to Scala have.

---

How can we solve this mess?

scala.functional

---

- prettify
- check code examples

---


Keep on talking, you have no idea what your ideas may shape the future of a Language, community and in general the work people do.