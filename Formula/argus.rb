class Argus < Formula
  desc "JVM diagnostic toolkit — 66 commands, health diagnosis, GC analysis, profiling, interactive TUI"
  homepage "https://github.com/rlaope/Argus"
  url "https://github.com/rlaope/Argus/releases/download/v1.2.1/argus-cli-1.2.1-all.jar"
  sha256 "5f83e90741a7c191636cd6a7089ae7f95a0d86f6b79d0ea8b3068d5ef63b1f6f"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "argus-cli-1.2.1-all.jar" => "argus-cli.jar"

    (bin/"argus").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk@21"].opt_bin}/java" -jar "#{libexec}/argus-cli.jar" "$@"
    EOS
  end

  test do
    system "#{bin}/argus", "--version"
  end
end
