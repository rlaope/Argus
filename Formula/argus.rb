class Argus < Formula
  desc "JVM Monitoring Orchestration Tools — diagnostic CLI, dashboard, TUI, and rule-based doctor"
  homepage "https://github.com/rlaope/Argus"
  url "https://github.com/rlaope/Argus/releases/download/v1.4.0/argus-cli-1.4.0-all.jar"
  sha256 "42ae225ebc68670097fe5adcf26872db089895df2b594e58b03d15415e4b0901"
  license "MIT"

  depends_on "openjdk@21"

  def install
    libexec.install "argus-cli-1.4.0-all.jar" => "argus-cli.jar"

    (bin/"argus").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk@21"].opt_bin}/java" -jar "#{libexec}/argus-cli.jar" "$@"
    EOS
  end

  test do
    system "#{bin}/argus", "--version"
  end
end
