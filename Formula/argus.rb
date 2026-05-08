class Argus < Formula
  desc "JVM diagnostic toolkit — 66 commands, health diagnosis, GC analysis, profiling, interactive TUI"
  homepage "https://github.com/rlaope/Argus"
  url "https://github.com/rlaope/Argus/releases/download/v1.3.0/argus-cli-1.3.0-all.jar"
  sha256 "f2e3ce15bf8f95a8c0e4d0d5da26c6d5ae8321295de41af1758e8516c8627a4e"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "argus-cli-1.3.0-all.jar" => "argus-cli.jar"

    (bin/"argus").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk@21"].opt_bin}/java" -jar "#{libexec}/argus-cli.jar" "$@"
    EOS
  end

  test do
    system "#{bin}/argus", "--version"
  end
end
