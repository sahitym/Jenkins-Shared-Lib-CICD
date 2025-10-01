// vars/notify.groovy
// Simple Slack notifier (requires Slack plugin configured in Jenkins)
// Usage: notify("SUCCESS")
def call(String status = 'SUCCESS') {
  def color = status == 'SUCCESS' ? '#36a64f' : (status == 'UNSTABLE' ? '#daa038' : '#a30200')
  def message = "${env.JOB_NAME} [${env.BUILD_NUMBER}] (${env.BRANCH_NAME ?: 'nobranch'}) - ${status} - ${env.BUILD_URL}"
  // If Slack plugin is configured use slackSend, otherwise just echo
  try {
    slackSend color: color, message: message
  } catch (err) {
    echo "slackSend failed or not configured: ${err}"
    echo message
  }
}
