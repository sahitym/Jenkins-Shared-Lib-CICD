// vars/ciPipeline.groovy
def call(Map params = [:]) {
  // Defaults
  params.registry         = params.get('registry', '848049623459.dkr.ecr.us-east-2.amazonaws.com/java-app-demo')
  params.imageTag         = params.get('imageTag', "${env.BRANCH_NAME ?: 'dev'}-${env.BUILD_NUMBER ?: '0'}-${env.GIT_COMMIT?.take(7) ?: 'local'}")
  params.awsCredentialsId = params.get('awsCredentialsId', 'aws-ecr-creds')
  params.sonarServerId    = params.get('sonarServerId', 'sonar-server')
  params.sonarTokenCredId = params.get('sonarTokenCredId', 'sonar-token')
  params.awsRegion        = params.get('awsRegion', 'us-east-2')
  params.clusterName      = params.get('clusterName', 'EKS')
  params.deployDir        = params.get('deployDir', "${env.WORKSPACE}/deploy")

  // Single node label for all stages (override by passing nodeLabel)
  params.nodeLabel        = params.get('nodeLabel', 'Web-server')

  node(params.nodeLabel) {
    stage('Checkout') {
      checkout scm
    }

    stage('Build & Unit Tests') {
      sh "mvn -B -DskipTests=false clean package"
    }

    stage('Static Analysis') {
      if (params.sonarServerId && params.sonarTokenCredId) {
        try {
          withCredentials([string(credentialsId: params.sonarTokenCredId, variable: 'SONAR_TOKEN')]) {
            withSonarQubeEnv(params.sonarServerId) {
              sh '''
                echo "=== SonarQube ==="
                mvn -B clean verify sonar:sonar -Dsonar.login=$SONAR_TOKEN -Dsonar.host.url=$SONAR_HOST_URL -Dsonar.projectKey=java-web-app-demo-1
              '''
            }
          }
        } catch (err) {
          echo "SonarQube analysis failed: ${err}"
        }
      } else {
        echo "SonarQube not configured — skipping static analysis."
      }
    }

    stage('Build Docker Image') {
      script {
        def fullImage = "${params.registry}:${params.imageTag}"
        echo "Building image: ${fullImage}"
        def dockerHelper = new org.company.helper.DockerHelper(this)
        dockerHelper.buildImage(fullImage)
      }
    }

    stage('Scan Image (optional)') {
      echo "Image scanning stage — add Trivy or other scanner here if desired."
    }

    stage('Push Image to ECR') {
      script {
        def fullImage = "${params.registry}:${params.imageTag}"
        echo "Pushing image: ${fullImage} to ECR (region: ${params.awsRegion})"
        def dockerHelper = new org.company.helper.DockerHelper(this)
        dockerHelper.pushImageToECR(fullImage, params.awsCredentialsId, params.awsRegion)
      }
    }

    stage('Deploy') {
      script {
        def fullImage = "${params.registry}:${params.imageTag}"
        if (!fullImage?.trim()) {
          error("registry and imageTag must be provided")
        }
        echo "Deploying image -> ${fullImage}"
        def dockerHelper = new org.company.helper.DockerHelper(this)
        dockerHelper.Deploy(fullImage, params.awsCredentialsId ?: '', params.awsRegion ?: 'us-east-2')
      }
    }

    stage('Post Steps') {
      parallel cleanup: {
        stage('Cleanup Workspace') {
          cleanWs()
        }
      }
    }
  } // node
}
