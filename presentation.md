autoscale: true
build-lists: true

---

# Run Wild, Run Free! #


(A team's journey over Scala's FP emerging patterns)

~

@raulraja CTO @47deg

---

## What is this about? ##

I'm gonna run by you some code examples that illustrate some of the 
pains that teams and individuals go through when working with Scala in production.

Compiled since 2013 with about ~10 teams with 5 - 6  devs per team 
+ many conf conversations.

---

## What this is about ##

A code centric report about: 

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

```scala
import rwrf.utils._
import scala.concurrent.Future

type Service[Req, Rep] = Req => Future[Rep]
```

A lot of current architectures are based on variations of this.

---

## Let-s build something 

### Model

```scala
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

```scala
case class NotFound(msg : String) extends RuntimeException(msg)
case class DuplicateFound(msg : String) extends RuntimeException(msg)
case class TimeoutException(msg : String) extends RuntimeException(msg)
case class HostNotFoundException(msg : String) extends RuntimeException(msg)
```

---

### DB backend

```scala
def fetchRemoteUser(userId: UserId) : User =
    throw NotFound(s"user not found with id : $userId")

def fetchRemoteAddress(addressId: AddressId) : Address =
    throw DuplicateFound(s"address duplicate found")
```

---

### Services

Here is where people start tripping

```scala
val fetchUser: Service[UserId, User] =
    (userId: UserId) => Future {
        fetchRemoteUser(userId)
    }
```

---

### Services

Let's try again

```scala

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

```scala
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

- Attempt to `Future#recover`
- Model known exceptional cases via nested `Option`, `Either` or `Try`

---

### Error handling

Those who attempt to recover may do and succeed

```scala
fetchUserInfo(1L) recover {
  case e : NotFound => println("Exception dealt with!")
}
```

---

### Error handling

But if you don't know the impl details and attempt to recover

```scala
fetchAddress(1L) recover {
  case e : NotFound => println("Exception dealt with!")
}
```

---

### Error handling

But if you don't know the impl details and attempt to recover

```scala
fetchAddress(1L) recover {
  case _ : NotFound => println("Exception dealt with!")
}
```

---

### Error handling

What starts as a trivial piece of code quickly becomes

```scala
import scala.util.control._

fetchAddress(1L) recover {
  case _ : NotFound => ???
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

```scala
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

The known case is that we don't know if things will be there or not.
So... `Option` it is.

```scala
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

```scala
scala> val fetchUserInfo: Service[UserId, (User, Address)] =
     |     (userId: UserId) =>
     |         for {
     |             user <- fetchUser(userId)
     |             address <- fetchAddress(user.addressId)
     |         } yield (user, address)
<console>:37: error: value addressId is not a member of Option[User]
                   address <- fetchAddress(user.addressId)
                                                ^
```

---

### Error handling

Most folks wrestle with the for comprehension but end up doing:

```scala
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

As new requirements are added you start seeing this everywhere

```scala
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

```html
<img src="../../../../../../../../../../../../../../../../../assets/logo.png" />
```

---

### Error handling

Code starts looking like

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

---

### Error handling

At this point choose your own adventure:

- It compiles and runs, I don't care! (╯°□°）╯︵ ┻━┻
- We should ask for help, things are getting out of control! 
- FP folks (if any around) suggest they use `Monad Transformers` ☠

---

### Error handling

- I've heard several times folks get introduced to cats or scalaz through:
    - `OptionT`
    - `EitherT`  
    - `Validation` (There is a reason why people get puzzled when |@| is moved around)

---

### Error handling

For the lucky ones your code starts looking nice again

```scala
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

They learn about the importance of algebraic design, ADTs and sealed hierarchies

```scala
sealed abstract class AppException(msg : String) extends Product with Serializable
final case class NotFound(msg : String) extends AppException(msg)
final case class DuplicateFound(msg : String) extends AppException(msg)
final case class TimeoutException(msg : String) extends AppException(msg)
final case class HostNotFoundException(msg : String) extends AppException(msg)
```

---

### Error handling

Ans since `Option` is not expressive enough for some cases

```scala
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

They grow their knowledge beyond `OptionT` and start discovering 
the same patterns apply to other nested types

```scala
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

### Did we need `Future` for these services?

People are reporting strange behaviors in tests.

---
