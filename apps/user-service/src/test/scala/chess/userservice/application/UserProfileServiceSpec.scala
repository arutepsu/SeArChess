package chess.userservice.application

import chess.userservice.domain.{ExternalAccountLink, UserProfile}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import scala.collection.mutable

class UserProfileServiceSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def makeService(): (UserProfileService, StubUserProfileRepository, StubExternalAccountLinkRepository) =
    val profileRepo = StubUserProfileRepository()
    val linkRepo    = StubExternalAccountLinkRepository()
    (UserProfileService(profileRepo, linkRepo), profileRepo, linkRepo)

  "getOrCreateProfile" should "create a new profile on first call" in {
    val (svc, repo, _) = makeService()
    val profile = svc.getOrCreateProfile("sub-001", "Alice", Some("alice@example.com")).value
    profile.keycloakSubject shouldBe "sub-001"
    profile.displayName     shouldBe "Alice"
    profile.email           shouldBe Some("alice@example.com")
    repo.store should have size 1
  }

  it should "return the existing profile on repeated calls" in {
    val (svc, repo, _) = makeService()
    val p1 = svc.getOrCreateProfile("sub-001", "Alice", Some("alice@example.com")).value
    val p2 = svc.getOrCreateProfile("sub-001", "Alice Updated", None).value
    p1.userId shouldBe p2.userId
    repo.store should have size 1
  }

  "setManualLichessLink" should "create a new link with verificationSource ManualDev" in {
    val (svc, _, linkRepo) = makeService()
    val userId = UUID.randomUUID()
    val link = svc.setManualLichessLink(userId, "alice_chess").value
    link.provider           shouldBe "Lichess"
    link.externalUsername   shouldBe "alice_chess"
    link.verified           shouldBe false
    link.verificationSource shouldBe "ManualDev"
    linkRepo.store should have size 1
  }

  it should "update an existing link when called again for the same user" in {
    val (svc, _, linkRepo) = makeService()
    val userId = UUID.randomUUID()
    val first  = svc.setManualLichessLink(userId, "alice_chess").value
    val second = svc.setManualLichessLink(userId, "alice_chess_v2").value
    second.linkId          shouldBe first.linkId
    second.externalUsername shouldBe "alice_chess_v2"
    linkRepo.store should have size 1
  }

  "deleteLink" should "remove a link" in {
    val (svc, _, linkRepo) = makeService()
    val userId = UUID.randomUUID()
    svc.setManualLichessLink(userId, "alice_chess").value
    svc.deleteLink(userId, "Lichess").value
    linkRepo.store shouldBe empty
  }

  "getLinksForUser" should "return all links for a user" in {
    val (svc, _, _) = makeService()
    val userId = UUID.randomUUID()
    svc.setManualLichessLink(userId, "alice_chess")
    val links = svc.getLinksForUser(userId).value
    links should have size 1
    links.head.externalUsername shouldBe "alice_chess"
  }

  // ── In-memory stubs ─────────────────────────────────────────────────────────

  private class StubUserProfileRepository extends UserProfileRepository:
    val store: mutable.Map[String, UserProfile] = mutable.Map.empty

    override def findBySubject(sub: String): Either[String, Option[UserProfile]] =
      Right(store.get(sub))

    override def insert(profile: UserProfile): Either[String, Unit] =
      store.put(profile.keycloakSubject, profile)
      Right(())

    override def updateDisplayNameAndEmail(userId: UUID, displayName: String, email: Option[String]): Either[String, Unit] =
      store.values.find(_.userId == userId) match
        case None    => Left("Profile not found")
        case Some(p) =>
          store.put(p.keycloakSubject, p.copy(displayName = displayName, email = email))
          Right(())

  private class StubExternalAccountLinkRepository extends ExternalAccountLinkRepository:
    val store: mutable.Map[(UUID, String), ExternalAccountLink] = mutable.Map.empty

    override def findAllByUserId(userId: UUID): Either[String, List[ExternalAccountLink]] =
      Right(store.values.filter(_.userId == userId).toList)

    override def findByUserAndProvider(userId: UUID, provider: String): Either[String, Option[ExternalAccountLink]] =
      Right(store.get((userId, provider)))

    override def insert(link: ExternalAccountLink): Either[String, Unit] =
      store.put((link.userId, link.provider), link)
      Right(())

    override def update(link: ExternalAccountLink): Either[String, Unit] =
      store.put((link.userId, link.provider), link)
      Right(())

    override def delete(userId: UUID, provider: String): Either[String, Unit] =
      store.remove((userId, provider))
      Right(())
