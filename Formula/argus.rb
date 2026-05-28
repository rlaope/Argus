class Argus < Formula
  desc "JVM Monitoring Orchestration Tools — diagnostic CLI, dashboard, TUI, and rule-based doctor"
  homepage "https://github.com/rlaope/Argus"
  url "https://github.com/rlaope/Argus/releases/download/v1.5.0/argus-cli-1.5.0-all.jar"
  sha256 "501cfb9dd612ec335186fe05649259fcecff193552e62be2820b26302cdbeea9"
  license "MIT"

  depends_on "openjdk@21"

  def install
    libexec.install "argus-cli-1.5.0-all.jar" => "argus-cli.jar"

    (bin/"argus").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk@21"].opt_bin}/java" -jar "#{libexec}/argus-cli.jar" "$@"
    EOS
  end

  test do
    system "#{bin}/argus", "--version"
  end
end
