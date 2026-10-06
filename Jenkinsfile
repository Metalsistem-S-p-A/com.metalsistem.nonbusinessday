pipeline {
    agent {
        label 'master'
    }

    options {
        timestamps()
    }

    stages {
        stage('Build') {
            steps {
                sh '''
                    mvn clean verify \
                        -Drepos=var/lib/jenkins/workspace \
                        -Drepos.idempiere=var/lib/jenkins/workspace/idempiere \
                        -Drepos.idempierebase=var/lib/jenkins/workspace \
                        -Drepos.msplugins=var/lib/jenkins/workspace
                '''
            }
        }
    }
}
