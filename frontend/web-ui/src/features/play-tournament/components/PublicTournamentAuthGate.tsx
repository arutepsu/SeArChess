// Superseded by ProtectedRoute (src/app/ProtectedRoute.tsx) which auto-redirects
// to Keycloak login instead of showing a static sign-in wall.
// AppRoutes.tsx uses ProtectedRoute directly; this file is kept as a passthrough.
export { default } from "../../../app/ProtectedRoute";
