pipeline {
    agent {
        node {
            label CONFIGURATION.deployAgent
        }
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))
    }
    stages {
        // Define a list of applications
        def applications = ['application_1', 'application_2', 'application_3', 'application_4']

        // Iterate over each application
        for (def currentAppName in applications) {
            stage("Get Image Deployed - ${currentAppName}") {
                steps {
                    script {
                        // Update the application name dynamically
                        sh 'gcloud beta container cluster get-credentials ' + CONFIGURATION.clusterName + ' --region ' + CONFIGURATION.region + ' --project ' + CONFIGURATION.projectId

                        def imageDeployed = sh(
                            script: 'https_proxy=10.130.183.2:8443 kubectl -n es-portal get deployment ' + currentAppName + ' -o jsonpath=(..image)',
                            returnStdout: true
                        ).trim();

                        def versionDeployed = sh(
                            script: 'https_proxy=10.130.183.2:8443 kubectl -n es-portal get deployment ' + currentAppName + ' -o jsonpath=(..appVersion)',
                            returnStdout: true
                        ).trim();

                        // Rest of the script...

                        echo "Image Deployed for ${currentAppName}: ${imageDeployed}"
                        echo "Version Deployed for ${currentAppName}: ${versionDeployed}"

                        // Rest of the script...
                    }
                }
            }

            stage("Get Image Info - ${currentAppName}") {
                steps {
                    script {
                        def imageTagsInfoStr = sh(
                            script: 'gcloud container images list-tags ' + IMAGE_NAME + ' --format=json',
                            returnStdout: true
                        ).trim()

                        IMAGE_INFO = readJSON(text: imageTagsInfoStr)
                        echo "IMAGE_INFO for ${currentAppName}: ${IMAGE_INFO}"
                    }
                }
            }
        }
    }
}
