import Keycloak from "keycloak-js";

const keycloak = new Keycloak({
  url: "http://localhost:8080",
  realm: "searchess",
  clientId: "searchess-web",
});

(window as any).keycloak = keycloak;

export default keycloak;