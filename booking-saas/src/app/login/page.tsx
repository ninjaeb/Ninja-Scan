import { AuthForm } from "@/components/AuthForm";
import { login } from "@/app/actions/auth";

export const metadata = { title: "Log in — Bookly" };

export default function LoginPage() {
  return <AuthForm mode="login" action={login} />;
}
