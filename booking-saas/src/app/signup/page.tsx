import { AuthForm } from "@/components/AuthForm";
import { signup } from "@/app/actions/auth";

export const metadata = { title: "Sign up — Bookly" };

export default function SignupPage() {
  return <AuthForm mode="signup" action={signup} />;
}
