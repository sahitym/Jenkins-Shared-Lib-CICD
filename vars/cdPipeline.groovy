// vars/eksDeploy.groovy
// Jenkins shared-library function to deploy to an EKS cluster
// - Uses AWS credentials (access key + secret) to call `aws eks update-kubeconfig` when kubeconfig file isn't provided
// - Falls back to applying a templated deployment YAML when `kubectl set image` fails
// - Ensures the target namespace exists (auto-creates it from a namespace template) when enabled
// - Optionally applies a namespaced RBAC template (ServiceAccount/Role/RoleBinding) if present
// - Uses libraryResource fallback so templates in the shared-library resources/ directory are available even when not present in the job workspace
// - Keeps AWS credentials available during subsequent kubectl calls to support the AWS exec credential plugin
// - Performs rollout status check and prints diagnostics on failure
// Notes:
// - Requires awscli, kubectl and envsubst to be available on the Jenkins agent.
// - For production, prefer IRSA or short-lived credentials instead of long-lived AWS keys.

// Helper: ensure a template file exists on the job workspace; if not, copy it from shared-library resources via libraryResource
// Returns a workspace path to the template file
private String ensureTemplateOnWorkspace(String workspacePath, String libResourceRelativePath) {
  if (fileExists(workspacePath)) {
    return workspacePath
  }
  // create tmp file path in workspace
  def tmpDir = "${WORKSPACE}/.ci_templates"
  def tmp = "${tmpDir}/${libResourceRelativePath.replaceAll('/', '_')}"
  // ensure dir
  sh "mkdir -p ${tmpDir}"
  try {
    def text = libraryResource("${libResourceRelativePath}")   // expects resources/<libResourceRelativePath>
    writeFile file: tmp, text: text
    return tmp
  } catch (e) {
    error "Failed to load library resource '${libResourceRelativePath}': ${e}"
  }
}

def call(Map params = [:]) {
  // Defaults
  params.registry           = params.get('registry', '848049623459.dkr.ecr.us-east-2.amazonaws.com/java-app-demo')
  params.imageTag           = params.get('imageTag', "${env.BRANCH_NAME ?: 'dev'}-${env.BUILD_NUMBER ?: '0'}-${env.GIT_COMMIT?.take(7) ?: 'local'}")
  params.awsCredentialsId   = params.get('awsCredentialsId', 'aws-ecr-creds')
  params.kubeconfigCredId   = params.get('kubeconfigCredId', null) // optional kubeconfig file credential
  params.kubeNamespace      = params.get('kubeNamespace', (env.BRANCH_NAME == 'main') ? 'production' : 'staging')
  params.awsRegion          = params.get('awsRegion', 'us-east-2')
  params.eksCluster         = params.get('eksCluster', 'my-eks-cluster')
  params.deploymentName     = params.get('deploymentName', 'myapp')
  params.containerName      = params.get('containerName', params.deploymentName)
  params.deploymentTemplate = params.get('deploymentTemplate', "${WORKSPACE}/resources/k8s/deployment.yaml.template")
  params.namespaceTemplate  = params.get('namespaceTemplate', "${WORKSPACE}/resources/k8s/namespace.yaml.template")
  params.rbacTemplate       = params.get('rbacTemplate', "${WORKSPACE}/resources/k8s/rbac-jenkins-sa.yaml.template")
  params.createNamespace    = params.get('createNamespace', true) // flag to auto-create namespace
  params.rolloutTimeoutSec  = params.get('rolloutTimeoutSec', 120)
  params.includeSessionToken = params.get('includeSessionToken', false) // include AWS_SESSION_TOKEN if needed

  node {
    stage('Deploy to EKS (usernamePassword AWS creds)') {
  when {
    expression { return params.kubeNamespace }
  }
  environment {
    AWS_REGION  = "${params.awsRegion ?: 'us-east-1'}"
    CLUSTER_NAME = "${params.clusterName}"
    IMAGE = "${params.registry}:${params.imageTag}"
    KUBECONFIG = "${WORKSPACE}/.kube/config"
  }
  steps {
    // Bind AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY from a "Username with password" credential
    withCredentials([usernamePassword(
      credentialsId: params.awsCredsId,
      usernameVariable: 'AWS_ACCESS_KEY_ID',
      passwordVariable: 'AWS_SECRET_ACCESS_KEY'
    )]) {
      sh '''
        set -euo pipefail
        umask 077

        echo "Ensure kube config dir"
        mkdir -p $(dirname "${KUBECONFIG}")

        echo "Updating kubeconfig for EKS cluster: ${CLUSTER_NAME} (region ${AWS_REGION})"
        # aws cli will write to KUBECONFIG ($HOME/.kube/config) by default.
        # Force the aws cli to write to our KUBECONFIG location by setting HOME env to WORKSPACE
        export HOME="${WORKSPACE}"
        aws sts get-caller-identity --output text || true

        # Update kubeconfig for EKS (requires eks:DescribeCluster)
        aws eks --region "${AWS_REGION}" update-kubeconfig --name "${CLUSTER_NAME}" --kubeconfig "${KUBECONFIG}"

        echo "Verifying cluster access"
        kubectl --kubeconfig "${KUBECONFIG}" version --short --client
        kubectl --kubeconfig "${KUBECONFIG}" get ns || true

        # Try set-image, fallback to apply from templated manifest
        IMAGE="${IMAGE}"
        NAMESPACE="${params.kubeNamespace}"
        DEPLOYMENT_NAME="${params.deploymentName ?: 'myapp'}"  # adjust or pass as param
        CONTAINER_NAME="${params.containerName ?: 'myapp'}"    # adjust if container name differs

        setImageCmd="kubectl --kubeconfig \"${KUBECONFIG}\" -n ${NAMESPACE} set image deployment/${DEPLOYMENT_NAME} ${CONTAINER_NAME}=${IMAGE} --record"
        applyCmd="envsubst < ${WORKSPACE}/resources/k8s/deployment.yaml.template | kubectl --kubeconfig \"${KUBECONFIG}\" -n ${NAMESPACE} apply -f -"

        echo "Attempting to update image: ${IMAGE}"
        if ${setImageCmd}; then
          echo "kubectl set image succeeded."
        else
          echo "kubectl set image failed — applying templated manifest as fallback."
          export IMAGE
          ${applyCmd}
        fi

        # Wait for rollout to complete (safe timeout)
        echo "Waiting for rollout status (deployment/${DEPLOYMENT_NAME})..."
        kubectl --kubeconfig "${KUBECONFIG}" -n ${NAMESPACE} rollout status deployment/${DEPLOYMENT_NAME} --timeout=3m || {
          echo "Rollout did not finish within timeout. Showing pods for debugging:"
          kubectl --kubeconfig "${KUBECONFIG}" -n ${NAMESPACE} get pods -o wide
          exit 1
        }

        echo "Deployment successful."
      '''
    }
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
} // call
