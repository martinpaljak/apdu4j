workflow "Default workflow for pushes" {
  on = "push"
  resolves = "release"
}

action "mvn" {
  needs = "mvn-11"
  uses = "docker://maven:3.6.1-jdk-8"
  runs = "mvn"
  args = "-U -B verify"
}

action "mvn-11" {
  uses = "docker://maven:3.6.1-jdk-11"
  runs = "mvn"
  args = "-U -B verify"
}

action "on-tag" {
  needs = "mvn"
  # Filter for tag
  uses = "actions/bin/filter@master"
  args = "tag"
}


action "release" {
  needs = "on-tag"
  uses = "martinpaljak/actions/deploy-release@master"
  args = "tool/target/apdu4j.jar"
  secrets = ["GITHUB_TOKEN"]
}
