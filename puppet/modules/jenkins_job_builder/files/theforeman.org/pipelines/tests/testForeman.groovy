node('fast') {

    stage('Setup Git Repos') {

        git url: "https://github.com/theforeman/foreman", branch: "develop"

    }

    stage("Setup RVM") {

        withRVM([
            'rvm gemset empty --force',
            'gem update --no-ri --no-rdoc',
            'gem install bundler --no-ri --no-rdoc'
        ])

    }

    stage('Configure Environment') {

        try {
            sh "cp config/settings.yaml.example config/settings.yaml"
            sh "sed -i 's/:locations_enabled: false/:locations_enabled: true/' config/settings.yaml"
            sh "sed -i 's/:organizations_enabled: false/:organizations_enabled: true/' config/settings.yaml"

            sh "cp $HOME/postgresql.db.yaml config/database.yml"

            sh "sed -i \"s/database:.*/database: ${gemset()}-test/\" config/database.yml"
            sh """
cat <<EOT >> config/database.yml
development:
adapter: postgresql
database: ${gemset()}-development
username: foreman
password: foreman
host: localhost
template: template0

production:
adapter: postgresql
database: ${gemset()}-development
username: foreman
password: foreman
host: localhost
template: template0
EOT
            """
        } catch (all) {
            cleanup()
        }

    }

    stage('Configure Database') {

        try {

            withRVM([
                'bundle update',
                'bundle exec rake db:drop db:create -q',
                'bundle exec rake db:migrate -q'
            ])

        } catch (all) {

            cleanup()

        }

    }

    try {

        stage('Run Tests') {

            parallel (
                'ruby-2.1/postgresql': { withRVM(['bundle exec rake jenkins:unit jenkins:integration TESTOPTS="-v"']) },
                'ruby-2.2/postgresql': { withRVM(['bundle exec rake jenkins:unit jenkins:integration TESTOPTS="-v"']) },
                'ruby-2.3/postgresql': { withRVM(['bundle exec rake jenkins:unit jenkins:integration TESTOPTS="-v"']) },
                'ruby-2.4/postgresql': { withRVM(['bundle exec rake jenkins:unit jenkins:integration TESTOPTS="-v"']) },
                'ruby-2.4/mysql': { withRVM(['bundle exec rake jenkins:unit TESTOPTS="-v"']) },
                'ruby-2.4/sqlite3': { withRVM(['bundle exec rake jenkins:unit TESTOPTS="-v"']) }
            )

        }

        stage('Generate Source') {

            withRVM(['bundle exec rake pkg:generate_source'])

        }

    } finally {

        cleanup()

        archiveArtifacts artifacts: "Gemfile.lock pkg/*"
        junit keepLongStdio: true, testResults: 'jenkins/reports/unit/*.xml'

    }

}

def gemset() {
    "${JOB_NAME}-${BUILD_ID}"
}

def withRVM(commands) {

    commands = commands.join("\n")

    sh """#!/bin/bash -l
        rvm use ruby-2.2@${gemset()} --create
        ${commands}
    """
}

def cleanup() {
    stage('Cleanup') {
        dir('foreman') {
            try {

                sh "rm -rf node_modules/"
                withRVM(['bundle exec rake db:drop DISABLE_DATABASE_ENVIRONMENT_CHECK=true'])

            } finally {

                withRVM([
                    "rvm gemset delete ${gemset()} --force",
                ])

            }
        }
    }
}
