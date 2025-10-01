# Jenkins Shared Library - CI Pipeline-

This repository is a Jenkins Shared Library implementing a reusable CI/CD pipeline for containerized Java applications (Maven). Drop into Jenkins as a Global Pipeline Library and call from your repo's `Jenkinsfile`.

## Structure
- `vars/cicdPipeline.groovy` - main pipeline entrypoint
- `vars/notify.groovy` - notification helper (Slack)
- `src/org/company/helper/DockerHelper.groovy` - utility class for Docker build/push
- `resources/k8s/deployment.yaml.template` - k8s deployment template (image placeholder)

## Installation
1. Add this repository as a **Global Pipeline Library**:
   - Jenkins → Manage Jenkins → Configure System → Global Pipeline Libraries
   - Name: `shared-lib` (example)
   - Default branch: `release-V1.0`
   - Set retrieval method (e.g., Modern SCM), point to this Git repo.

2. Create required credentials in Jenkins:
   - `docker-registry-creds` (username/password or token)
   - `kubeconfig` (secret file holding kubeconfig for deploy target)
   - `sonar-token` (string secret) and add SonarQube server in Jenkins (if using Sonar)

3. Ensure your Jenkins agents have:
   - Docker CLI
   - kubectl (if deploying to k8s)
   - envsubst or an alternative templating tool
   - Maven (or use a containerized build)

## Usage (Jenkinsfile example)
```groovy
@Library('shared-lib@main') _
pipeline {
  agent any
  stages {
    stage('Run Shared CI Pipeline') {
      steps {
        script {
          ciPipeline(
            registry: 'myregistry.example.com/myapp',
            imageTag: "${env.BRANCH_NAME}-${env.BUILD_NUMBER}",
            dockerCredsId: 'docker-registry-creds',
            kubeconfigCredId: 'kubeconfig',
            kubeNamespace: (env.BRANCH_NAME == 'main') ? 'production' : 'staging',
            sonarServerId: 'SonarQube',        // optional
            sonarTokenCredId: 'sonar-token'    // optional
          )
        }
      }
    }
  }
  post {
    always { script { notify(currentBuild.currentResult) } }
  }
}
```

## Customize
- Replace `myapp` deployment name with your actual deployment.
- Add scanning steps (Trivy) or signing (cosign) in the `ciPipeline`.
- Use Helm instead of plain kubectl for more advanced deployments.

## Notes
- This library expects `docker` and `kubectl` binaries on the agent nodes (or use Kubernetes plugin / docker agents).
- Keep the shared library versioned; reference a release tag from `@Library('shared-lib@v1.0.0')` for stability.
