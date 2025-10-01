package org.company.helper

class DockerHelper implements Serializable {
  def script
  DockerHelper(script) { this.script = script }

  void buildImage(String image) {
    script.echo "Building Docker image: ${image}"
    script.sh "docker build -t ${image} ."
  }

  void pushImage(String image, String credentialsId) {
    script.echo "Pushing Docker image: ${image}"
    script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
      def registryHost = image.tokenize('/')[0]
      script.sh('''#!/bin/bash
set -euo pipefail
echo "$DOCKER_PASS" | docker login ''' + registryHost + ''' -u "$DOCKER_USER" --password-stdin
docker push ''' + image + '''
docker logout ''' + registryHost + '''
''')
    }
  }

  void pushImageToECR(String image, String awsCredentialsId, String region = 'us-east-2') {
    script.echo "Pushing Docker image to ECR: ${image}"
    def registryHost = image.tokenize('/')[0]

    def pushCmd = '''
#!/bin/bash
set -euo pipefail
echo "Logging in to ECR"
aws ecr get-login-password --region ''' + region + ''' | docker login --username AWS --password-stdin ''' + registryHost + '''
docker push ''' + image + '''
docker logout ''' + registryHost + '''
'''

    if (awsCredentialsId?.trim()) {
      script.withCredentials([[
        $class: 'AmazonWebServicesCredentialsBinding',
        credentialsId: awsCredentialsId
      ]]) {
        script.sh(pushCmd)
      }
    } else {
      script.sh(pushCmd)
    }
  }

  /**
   * Deploy using native `docker compose`.
   *
   * @param imageFull full image reference (registry/repo:tag)
   * @param awsCredsId optional Jenkins AWS credentials id (for ECR login)
   * @param awsRegion AWS region for ECR
   */
  void Deploy(String imageFull, String awsCredsId, String awsRegion = 'us-east-2') {
    // Defensive check
    if (!imageFull || imageFull.trim() == '') {
      script.error("Deploy called with empty imageFull — ensure params.registry and params.imageTag are set")
    }

    script.echo "DockerHelper.Deploy -> image=${imageFull} region=${awsRegion} (awsCredsId provided=${awsCredsId ? 'yes' : 'no'})"

    // deployment dir (use env.DEPLOY_DIR if set, else workspace/deploy)
    def deployDir = script.env.DEPLOY_DIR ?: "${script.env.WORKSPACE}/deploy"
    script.sh "mkdir -p ${deployDir}"

    // compute registry host in Groovy (safe)
    def registryHost = imageFull.tokenize('/')[0]

    // Pull image (login to ECR) using AWS credentials if provided
    def pullCmd = """#!/bin/bash
set -euo pipefail
echo "ECR registry host: ${registryHost}"
aws ecr get-login-password --region ${awsRegion} | docker login --username AWS --password-stdin ${registryHost}
docker pull '${imageFull}'
"""

    try {
      if (awsCredsId?.trim()) {
        // Use Jenkins AWS credentials binding if provided
        script.withCredentials([[
          $class: 'AmazonWebServicesCredentialsBinding',
          credentialsId: awsCredsId
        ]]) {
          script.sh(pullCmd)
        }
      } else {
        script.echo "No AWS credentialsId provided; using agent environment AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY if present"
        script.sh(pullCmd)
      }
    } catch (err) {
      script.echo "ECR login / pull failed with: ${err}. Rethrowing."
      throw err
    }

    // Copy docker-compose.yml from workspace to deployDir if present
    def workspaceCompose = "${script.env.WORKSPACE}/docker-compose.yml"
    script.echo "Checking for ${workspaceCompose}"
    script.sh("""#!/bin/bash
set -e
if [ -f "${workspaceCompose}" ]; then
  echo "Copying docker-compose.yml to ${deployDir}"
  cp "${workspaceCompose}" "${deployDir}/docker-compose.yml"
else
  echo "No docker-compose.yml in workspace; assuming one already present in ${deployDir}"
fi
""")

    // last-good tag file
    def lastGoodFile = deployDir + "/.last_successful_tag"

    // Deploy command using native `docker compose`
    def deployCmd = """#!/bin/bash
set -euo pipefail
cd ${deployDir}
echo "Running 'docker compose' using file: \$(pwd)/docker-compose.yml"
export IMAGE_FULL='${imageFull}'

# Use native docker compose (requires plugin installed)
docker compose -f docker-compose.yml up -d --remove-orphans

echo '${imageFull}' > ${lastGoodFile}
echo "Deploy succeeded and recorded last-good image: ${imageFull}"
"""

    try {
      script.sh(deployCmd)
      script.echo "Deploy successful: ${imageFull}"
    } catch (deployErr) {
      script.echo "Deploy failed: ${deployErr}"
      // Attempt rollback to last good tag if present
      try {
        script.echo "Attempting rollback using ${lastGoodFile} if available"
        def rollbackCmd = """#!/bin/bash
set -euo pipefail
cd ${deployDir}
LAST_IMAGE=\$(cat ${lastGoodFile} 2>/dev/null || echo "")
if [ -z "\$LAST_IMAGE" ]; then
  echo "No last-good image recorded; cannot rollback"
  exit 2
fi
echo "Rolling back to \$LAST_IMAGE"
if ! docker image inspect "\$LAST_IMAGE" >/dev/null 2>&1; then
  echo "Attempting to pull rollback image: \$LAST_IMAGE"
  aws ecr get-login-password --region ${awsRegion} | docker login --username AWS --password-stdin ${registryHost}
  docker pull "\$LAST_IMAGE" || true
fi

# Rollback using native docker compose
docker compose -f docker-compose.yml up -d --remove-orphans
"""
        script.sh(rollbackCmd)
        script.error("Deployment failed but rollback to last-good image succeeded (marked build failed for investigation).")
      } catch (rollbackErr) {
        script.echo "Rollback failed or not possible: ${rollbackErr}"
        throw deployErr
      }
    }
  }
}
