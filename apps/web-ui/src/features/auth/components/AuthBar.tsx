import UserMenu from "./UserMenu";
import "./AuthBar.css";

interface AuthBarProps {
  onDemo?: () => void;
}

export default function AuthBar({ onDemo }: AuthBarProps) {
  return (
    <div className="auth-bar">
      <UserMenu onDemo={onDemo} dropdownAlign="left" />
    </div>
  );
}
