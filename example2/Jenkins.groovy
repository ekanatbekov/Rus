@Library('iwsSharedLibraries')
@Library('cicd-servicenow-automation')


Integer MAX_DAYS_IMAGE_CREATION = 13

def CONFIGURATION = envConfiguration.getEnvironmentConfig(ENVIRONMENT)
def APPLICATION = canApps.getApplication(APPLICATION_ID)

if (!APPLICATION) {
    APPLICATION = ausApps.getApplication(APPLICATION_ID)
}
if (!APPLICATION) {
    APPLICATION = ausApps.getApplication(APPLICATION_ID)
}

currentBuild.DisplayName = "${APPLICATION_ID}-${ENVIRONMENT}"

def CURRENT_TAG = ""
def isCurrentTagSnapshot = false

pipeline {
    agent {
        node {
            label CONFIGURATION.deployAgent
        }
    }
    options {
        builldDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '30'))
    }
    stages {
        stage('Get Image Deployed'){
            steps {
                script {
                    sh 'gcloud beta container cluster get-credentials '+ CONFIGURATION.clusterName +' --region'+ CONFIGURATION.region +' --project ' + CONFIGURATION.projectId

                    def imageDeployed = sh(
                        script: 'https_proxy='+ CONFIGURATION.kubectlProxy +' kubectl -n ' + CONFIGURATION.namespace + ' get deployment ' + APPLICATION.deploymentName + ' -o jsonpath=(..image)',
                        returnStdout: true
                    ).trim();

                    def versionDeployed = sh(
                        script: 'https_proxy='+ CONFIGURATION.kubectlProxy + 'kubectl -n ' + CONFIGURATION.namespace + ' get deployment ' + APPLICATION.deploymentName + ' -o jsonpath=(..appVersion)',
                        returnStdout; true
                    ).trim();

                    echo imageDeployed;
                    echo versionDeployed;

                    IMAGE_NAME = imageDeployed.split("@")[0];
                    echo IMAGE_NAME;
                    MULTIPLE_IMAGE_NAMES = IMAGE_NAME.split(" ");
                    if(MULTIPLE_IMAGE_NAMES.size() > 0){
                        for (int i = 0; i < MULTIPLE_IMAGE_NAMES.size(); i++) {
                            if(MULTIPLE_IMAGE_NAMES[i].contains("ews-iws")){
                                IMAGE_NAME=MULTIPLE_IMAGE_NAMES[i]
                                echo IMAGE_NAME
                                break
                            }
                        }
                    }

                    IMAGE_DIGEST = imageDeployed.split("@")[1];
                    echo IMAGE_DIGEST;
                    LIST_OF_IMAGE_DIGEST = IMAGE_DIGEST.split (" ");
                    if(LIST_OF_IMAGE_DIGEST.size() > 0){
                        for (int i = 0; i < LIST_OF_IMAGE_DIGEST.size(); i++) {
                            if(LIST_OF_IMAGE_DIGEST[i].contains("ews-iws")){
                                IMAGE_DIGEST=LIST_OF_IMAGE_DIGEST[i]
                                echo IMAGE_DIGEST
                                break
                            }
                        }
                    }

                    APP_VERSION = versionDeployed;
                }
            }
        }

        stage('Get image Info') {
            steps {
                script {
                    def imageTagsInfoStr = sh(
                        script: 'gcloud container images list-tags ' + IMAGE_NAME + ' --fromat=json'
                        returnStdout: true
                    ).trim()
                    IMAGE_INFO = readJSON(text: imageTagsInfoStr)
                    echo "IMAGE_INFO.........${IMAGE_INFO}"
                }
            }
        }
        stage('Validate Rules') {
            steps {
                script {
                    def imageContainTags = IMAGE_INFO.size() > 0;
                    if (imageContainTags) {
                        def imageTagRunningOnKubernetes
                        IMAGE_INFO.each { entry ->
                            if (entry.digest.equals(IMAGE_DIGEST)) {
                                imageTagRunningOnKubernetes = utils.jsonToImageObject(entry)
                                CURRENT_TAG = imageTagRunningOnKubernetes.tags
                                isCurrentTagSnapshot = CURRENT_TAG.toLowerCase().contains("snapshot")
                            }
                        }
                        if (imageTagRunningOnKubernetes) {
                            def imageAge = utils.daysBetweenDateAndToday(imageTagRunningOnKubernetes.creationDate)
                            imageDateIsOlderThanAllowed = imageAge >= MAX_DAYS_IMAGE_CREATION
                            echo "imageDateIsOlderThanAllowed......${imageDateIsOlderThanAllowed}"
                            if (!imageDateIsOlderThanAllowed) {
                                echo "Image ${IMAGE_NAME}@${IMAGE_DIGEST} is up-to-date. Creation date: ${imageTagRunningOnKubernetes.creationDate}. Age: ${imageAge} days"
                                currentBuild.result = 'SUCCESS'
                                return
                            } else {
                                echo "image ${IMAGE_NAME}@${IMAGE_DIGEST} is older than ${MAX_DAYS_IMAGE_CREATION} days. Creation date: ${imageTagRunningOnKubernetes.creationDate}. Age ${imageAge} days. A new image will be created"
                                currentBuild.result = 'SUCCESS'
                                return
                            }
                        }
                    }
                    echo "image ${IMAGE_NAME}:${IMAGE_DIGEST} not found. A new image will be generated"
                    imageDateIsOlderThanAllowed = true 
                }
            }
        }

        stage('Build new Image') {
            when { expression {imageDateIsOlderThanAllowed == true } }
            steps {
                script {
                    build job: "/Core/Applications/Build/Automations/container-image-rebuilds/rebuild-image-version"
                    parameters:[
                        string(name : 'version', value : "${APP_VERSION}"),
                        string(name : 'repo', value : "${APPLICATION.gitRepoName}"),
                        string(name : 'techStack'. value : "${APPLICATION.techStack.getName()}"),
                        string(name : 'nexusRebuildGroupId', value : "${APPLICATION.nexusRebuildGroupId}"),
                        string(name : 'nexusRebuildArtifactId', value : "${APPLICATION.nexusRebuildArtifactId}"),
                        string(name : 'nexusRebuildLocalPath', value : "${APPLICATION.nexusRebuildLocalPath}"),
                        string(name : 'nexusRebuildLocalFilename', value : "${APPLICATION.nexusRebuildLocalFilename}"),
                        string(name : 'nexusRebuildNPMArtifactPath', value : "${APPLICATION.nexusRebuildNPMArtifactPath}")
                        string(name : 'appDirectory', value : "${APPLICATION.appDirectory}")
                    ]
                }
            }
        }

        stage('Deploy new Image to Dev') {
            when { expression { imageDateIsOlderThanAllowed == true && CONFIGURATION.evnironment == "dev"} }
            steps {
                script {
                    def newImageStr = sh(
                        script: 'gcloud container images list-tags ' + IMAGE_NAME + ' --format=json --limit=1',
                        returnStdout: true
                    ).trim()
                    
                    def newImageGeneratedAsJson = readJSON(text: newImageStr)
                    NEW_IMAGE_GENERATED = untils.jsonToImageObject(newImageGeneratedAsJson[0])

                    CENTRAL_GCR_PREFIX = IMAGE_NAME.split("/")[3];
                }
                build(
                    job: "${CONFIGURATION.country}" + '/Application/Deploy/DEV-APP-DEPLOY-WITH-TAG'
                    parameters: [
                        string(name : 'centralGcrSubPrefix', value : "${CENTRAL_GCR_PREFIX}"),
                        string(name: 'artifactId', value : "${APPLICATION.artifactId}"),
                        string(name: 'appVersion', value : "${APP_VERSION}"),
                    ]
                )
            }
        }
        stage('Wait for DEV deploy to finish') {
            when { expression { imageDateIsOlderThanAllowed == true && CONFIGURATION.evnironment == "dev" } }
            steps {
                script {
                    DEV_NAMESPACE = ENVIRONMENT.split("-")[0] + "dev";
                }
                build(
                    job: 'Core/Application/Build/Automations/caontainer-image-rebuilds/WAIT_Until_Deploy_Is_Done',
                    parameters: [
                        string(name : 'namespace', value : "${DEV_NAMESPACE}"),
                        string(name : 'artifactId', value : "${APPLICATION.deploymentName}"),
                    ]
                )
            }
        }
    }
}
