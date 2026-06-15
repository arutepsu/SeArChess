import UserMenu from "./UserMenu";
import "./AuthBar.css";

export default function AuthBar() {
  return (
    <div className="auth-bar">
      <UserMenu dropdownAlign="left" />
    </div>
  );
}
