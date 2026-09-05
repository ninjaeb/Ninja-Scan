import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Bookly — Reservations & Bookings for Your Business",
  description:
    "Let customers book your services online. Manage services, availability, and reservations in one place.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body className="min-h-screen antialiased">{children}</body>
    </html>
  );
}
