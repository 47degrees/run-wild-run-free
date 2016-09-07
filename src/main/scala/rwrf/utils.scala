package rwrf

import cats.data.Xor
import monix.eval.Task
import simulacrum.typeclass

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
  * Created by raulraja on 9/6/16.
  */
object utils {

  @typeclass trait Capture[F[_]] {
    def capture[A](a: => A): F[A]
  }

  implicit def futureCapture(implicit ec : ExecutionContext) : Capture[Future] = new Capture[Future] {
    override def capture[A](a: => A): Future[A] = Future.apply(a)(ec)
  }

  implicit val taskCapture : Capture[Task] = new Capture[Task] {
    override def capture[A](a: => A): Task[A] = Task.evalOnce(a)
  }

  implicit val tryCapture : Capture[Try] = new Capture[Try] {
    override def capture[A](a: => A): Try[A] = Try(a)
  }

  implicit class FutureAwaitOps[A](f: Future[A]) {
    def await: A = Await.result(f, Duration.Inf)
  }

  /** Exceptions modeled as part of the unsealed Throwable hierarchy
    */
  trait ThrowableExceptions {

    case class NotFound(msg : String) extends RuntimeException(msg)
    case class DuplicateFound(msg : String) extends RuntimeException(msg)
    case class TimeoutException(msg : String) extends RuntimeException(msg)
    case class HostNotFoundException(msg : String) extends RuntimeException(msg)

  }

  trait SealedExceptions {

    sealed abstract class AppException(msg : String)
    final case class NotFound(msg : String) extends AppException(msg)
    final case class DuplicateFound(msg : String) extends AppException(msg)
    final case class TimeoutException(msg : String) extends AppException(msg)
    final case class HostNotFoundException(msg : String) extends AppException(msg)

  }

  object Model {

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

  }

  trait DBThrowingExceptions extends ThrowableExceptions {

    import Model._

    def fetchRemoteUser(userId: UserId) : User =
      throw NotFound(s"user not found with id : $userId")

    def fetchRemoteAddress(addressId: AddressId) : Address =
      throw DuplicateFound(s"address duplicate found")

  }

  /** Simulates a remote not under our control blocking API which returns unsafe results such as `null`
    *
    */
  trait DBReturningData {

    import Model._

    def fetchRemoteUser(userId: UserId) : User =
      if (userId == 1L) User(userId, 10L) else null

    def fetchRemoteAddress(addressId: AddressId) : Address = Address(addressId, 20L)

    def fetchRemotePostalCode(postalCodeId: PostalCodeId) : PostalCode = PostalCode(postalCodeId, 30L)

    def fetchRemoteRegion(regionId: RegionId) : Region = Region(regionId, 40L)

    def fetchRemoteCountry(countryId: CountryId) : Country = Country(countryId)

  }

  object ServicesVanilla
      extends DBThrowingExceptions {

    import Model._

    import scala.concurrent.ExecutionContext.Implicits.global

    type Service[A, B] = A => Future[B]

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

  }

  object ServicesOption
      extends DBReturningData {

    import Model._

    import scala.concurrent.ExecutionContext.Implicits.global

    type Service[A, B] = A => Future[B]

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

  }

  object ServicesEither
    extends DBReturningData
      with SealedExceptions {

    import Model._
    import cats.implicits._

    import scala.concurrent.ExecutionContext.Implicits.global

    type Service[A, B] = A => Future[B]

    def fetchUser: Service[UserId, NotFound Xor User] =
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

  }

  class ServicesCapture[M[_] : Capture]
    extends DBReturningData
      with SealedExceptions {

    import Model._
    import cats.implicits._

    type Service[F[_], A, B] = A => F[B]

    val fetchUser: Service[M, UserId, NotFound Xor User] =
      (userId: UserId) => Capture[M].capture {
        Option(fetchRemoteUser(userId))
          .fold(NotFound(s"User $userId not found").left[User])(x => x.right[NotFound])
      }

    val fetchAddress: Service[M, AddressId, NotFound Xor Address] =
      (addressId: AddressId) => Capture[M].capture {
        Option(fetchRemoteAddress(addressId))
          .fold(NotFound(s"Address $addressId not found").left[Address])(x => x.right[NotFound])
      }

    val fetchPostalCode: Service[M, PostalCodeId, NotFound Xor PostalCode] =
      (postalCodeId: PostalCodeId) => Capture[M].capture {
        Option(fetchRemotePostalCode(postalCodeId))
          .fold(NotFound(s"PostalCode $postalCodeId not found").left[PostalCode])(x => x.right[NotFound])
      }

    val fetchRegion: Service[M, RegionId, NotFound Xor Region] =
      (regionId: RegionId) => Capture[M].capture {
        Option(fetchRemoteRegion(regionId))
          .fold(NotFound(s"Region $regionId not found").left[Region])(x => x.right[NotFound])
      }

    val fetchCountry: Service[M, CountryId, NotFound Xor Country] =
      (countryId: CountryId) => Capture[M].capture {
        Option(fetchRemoteCountry(countryId))
          .fold(NotFound(s"Country $countryId not found").left[Country])(x => x.right[NotFound])
      }

  }

  object ServicesCapture {
    implicit def servicesInstance[M[_] : Capture] : ServicesCapture[M] = new ServicesCapture[M]
  }

  object FreeMonads {

    import Model._
    import cats._
    import cats.data._
    import cats.free._

    object Definitions extends SealedExceptions {

      sealed abstract class ServiceOp[A] extends Product with Serializable

      final case class FetchUser(userId: UserId) extends ServiceOp[NotFound Xor User]

      final case class FetchAddress(addressId: AddressId) extends ServiceOp[NotFound Xor Address]

      final case class FetchPostalCode(postalCodeId: PostalCodeId) extends ServiceOp[NotFound Xor PostalCode]

      final case class FetchRegion(regionId: RegionId) extends ServiceOp[NotFound Xor Region]

      final case class FetchCountry(countryId: CountryId) extends ServiceOp[NotFound Xor Country]

      type ServiceIO[A] = Free[ServiceOp, A]

      object ServiceOps {
        def fetchUser(userId: UserId): ServiceIO[NotFound Xor User] = Free.liftF(FetchUser(userId))

        def fetchAddress(addressId: AddressId): ServiceIO[NotFound Xor Address] = Free.liftF(FetchAddress(addressId))

        def fetchPostalCode(postalCodeId: PostalCodeId): ServiceIO[NotFound Xor PostalCode] = Free.liftF(FetchPostalCode(postalCodeId))

        def fetchRegion(regionId: RegionId): ServiceIO[NotFound Xor Region] = Free.liftF(FetchRegion(regionId))

        def fetchCountry(countryId: CountryId): ServiceIO[NotFound Xor Country] = Free.liftF(FetchCountry(countryId))

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
      }

    }

    object Runtime {

      import Definitions._

      def interpreter[M[_] : Capture : Monad : RecursiveTailRecM](implicit impl: ServicesCapture[M]): ServiceOp ~> M = new (ServiceOp ~> M) {
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
      }

    }

  }

}
