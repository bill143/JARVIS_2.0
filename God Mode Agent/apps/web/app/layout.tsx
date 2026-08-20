import type { Metadata } from "next";
import { JetBrains_Mono, Outfit, Syne } from "next/font/google";
import "./globals.css";
import { AuthProvider } from "@/lib/auth";

const syne = Syne({ subsets: ["latin"], weight: "800", variable: "--font-syne" });
const outfit = Outfit({ subsets: ["latin"], weight: "300", variable: "--font-outfit" });
const jetbrainsMono = JetBrains_Mono({ subsets: ["latin"], variable: "--font-mono-jb" });

export const metadata: Metadata = {
  title: "ECHO Command",
  description:
    "Phase 2 hardened multimodal AI agent - auth, policy, approvals, observability",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={`${syne.variable} ${outfit.variable} ${jetbrainsMono.variable}`}>
      <body>
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
