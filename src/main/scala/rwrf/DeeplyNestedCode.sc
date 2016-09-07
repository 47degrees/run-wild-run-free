import cats.data._
import rwrf.utils.Model._
import rwrf.utils.ServicesOption._
import rwrf.utils._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object services {

  /* won't compile
  val fetchUserInfo: Service[UserId, (User, Address)] =
    (userId: UserId) =>
      for {
        user <- fetchUser(userId)
        address <- fetchAddress(user.addressId)
      } yield (user, address)
      */

  val fetchUserInfo1: Service[UserId, Option[(User, Address)]] =
    (userId: UserId) =>
      fetchUser(userId) flatMap {
        case Some(user) => fetchAddress(user.addressId) flatMap {
          case Some(address) => Future.successful(Some((user, address)))
          case None => Future.successful(None)
        }
        case None => Future.successful(None)
      }

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


  val fetchUserInfoOptT: Service[UserId, Option[(User, Address, PostalCode, Region, Country)]] =
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

  import ServicesEither._

  val fetchUserInfoXorT: Service[UserId, NotFound Xor (User, Address, PostalCode, Region, Country)] =
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



}

services.fetchUserInfoOptT(1L).await
services.fetchUserInfoXorT(10L).await