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

  "setManualLichessLink" should "create a new link with verificationSource ManualDev and capability manual_dev" in {
    val (svc, _, linkRepo) = makeService()
    val userId = UUID.randomUUID()
    val link = svc.setManualLichessLink(userId, "alice_chess").value
    link.provider           shouldBe "Lichess"
    link.externalUsername   shouldBe "alice_chess"
    link.verified           shouldBe false
    link.verificationSource shouldBe "ManualDev"
    link.capability         shouldBe "manual_dev"
    linkRepo.store should have size 1
  }

  it should "update an existing ManualDev link and keep capability manual_dev and verified false" in {
    val (svc, _, linkRepo) = makeService()
    val userId = UUID.randomUUID()
    val first  = svc.setManualLichessLink(userId, "alice_chess").value
    val second = svc.setManualLichessLink(userId, "alice_chess_v2").value
    second.linkId              shouldBe first.linkId
    second.externalUsername    shouldBe "alice_chess_v2"
    second.verified            shouldBe false
    second.verificationSource  shouldBe "ManualDev"
    second.capability          shouldBe "manual_dev"
    linkRepo.store should have size 1
  }

  it should "downgrade an OAuth-verified link to manual_dev when username is updated via ManualDev" in {
    val (svc, _, linkRepo) = makeService()
    val userId = UUID.randomUUID()
    // Pre-seed an OAuthPKCE link directly in the repository (simulates a prior OAuth flow)
    val oauthLink = ExternalAccountLink(
      linkId             = UUID.randomUUID(),
      userId             = userId,
      provider           = "Lichess",
      externalId         = Some("alice_oauth"),
      externalUsername   = "alice_oauth",
      verified           = true,
      verificationSource = "OAuthPKCE",
      linkedAt           = Instant.now(),
      capability         = "identity_only"
    )
    linkRepo.store.put((userId, "Lichess"), oauthLink)

    val updated = svc.setManualLichessLink(userId, "alice_manual").value
    updated.externalUsername   shouldBe "alice_manual"
    updated.verified           shouldBe false
    updated.verificationSource shouldBe "ManualDev"
    updated.capability         shouldBe "manual_dev"
    linkRepo.store should have size 1
  }

  "deleteLink" should "remove a link" in {
    val (svc, _, linkRepo) = makeService()
    val userId = UUID.randomUUID()
    svc.setManualLichessLink(userId, "alice_chess").value
    svc.deleteLink(userId, "Lichess").value
    linkRepo.store shouldBe empty
  }

  "setNickname" should "set nickname and return updated profile" in {
    val (svc, repo, _) = makeService()
    val profile = svc.getOrCreateProfile("sub-nick", "Nick", None).value
    profile.onboardingRequired shouldBe true
    val updated = svc.setNickname(profile, "CoolKnight").value
    updated.nickname           shouldBe Some("CoolKnight")
    updated.onboardingRequired shouldBe false
    repo.store("sub-nick").nickname shouldBe Some("CoolKnight")
  }

  it should "reject nicknames that are too short" in {
    val (svc, _, _) = makeService()
    val profile = svc.getOrCreateProfile("sub-short", "Short", None).value
    svc.setNickname(profile, "ab").left.value should include("at least 3")
  }

  it should "reject nicknames with invalid characters" in {
    val (svc, _, _) = makeService()
    val profile = svc.getOrCreateProfile("sub-bad", "Bad", None).value
    svc.setNickname(profile, "hello world").left.value should include("only contain")
  }

  it should "reject a duplicate nickname (case-insensitive)" in {
    val (svc, _, _) = makeService()
    val p1 = svc.getOrCreateProfile("sub-p1", "P1", None).value
    val p2 = svc.getOrCreateProfile("sub-p2", "P2", None).value
    svc.setNickname(p1, "BigBoss").value
    svc.setNickname(p2, "BIGBOSS").left.value should include("already taken")
  }

  it should "allow the same user to re-set their own nickname" in {
    val (svc, _, _) = makeService()
    val profile = svc.getOrCreateProfile("sub-reuse", "Reuse", None).value
    svc.setNickname(profile, "FirstNick").value
    svc.setNickname(profile, "FirstNick").value.nickname shouldBe Some("FirstNick")
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

    override def findByNicknameCi(nickname: String): Either[String, Option[UserProfile]] =
      Right(store.values.find(_.nickname.exists(_.equalsIgnoreCase(nickname))))

    override def setNickname(userId: UUID, nickname: String): Either[String, Unit] =
      store.values.find(_.userId == userId) match
        case None    => Left("Profile not found")
        case Some(p) =>
          store.put(p.keycloakSubject, p.copy(nickname = Some(nickname)))
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

    override def findByLichessUsername(username: String): Either[String, Option[ExternalAccountLink]] =
      Right(None)

    override def delete(userId: UUID, provider: String): Either[String, Unit] =
      store.remove((userId, provider))
      Right(())
