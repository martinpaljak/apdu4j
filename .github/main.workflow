workflow "Default workflow for pushes" {
  on = "push"
  resolves = "release"
}

action "mvn" {
  uses = "docker://maven:3.6.0-jdk-8"
  runs = "mvn"
  args = "-U -B verify"
}


action "on-tag" {
  # Filter for tag
  needs = "mvn"
  uses = "actions/bin/filter@master"
  args = "tag"
}


action "release" {
  needs = "on-tag"
  uses = "martinpaljak/actions/deploy-release@master"
  args = "tool/target/apdu4j.jar"
  secrets = ["GITHUB_TOKEN"]
}
