class Argus < Formula
  desc "JVM diagnostic toolkit — 56 commands, health diagnosis, GC analysis, interactive TUI"
  homepage "https://github.com/rlaope/Argus"
  url "https://github.com/rlaope/Argus/releases/download/v1.0.0/argus-cli-1.0.0-all.jar"
  sha256 "PLACEHOLDER"  # Will be updated on release
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "argus-cli-1.0.0-all.jar" => "argus-cli.jar"

    (bin/"argus").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk@21"].opt_bin}/java" -jar "#{libexec}/argus-cli.jar" "$@"
    EOS
  end

  test do
    system "#{bin}/argus", "--version"
  end
end
