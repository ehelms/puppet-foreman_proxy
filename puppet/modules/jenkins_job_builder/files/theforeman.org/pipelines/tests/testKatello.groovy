pipeline {
    agent { label 'fast' }

    stages {
        stage('Setup Git Repos') {
            steps {
                dir('foreman') {
                    git url: "https://github.com/theforeman/foreman", branch: "develop"
                }


                git url: "https://github.com/katello/katello", branch: "master"
            }
        }
        stage("Setup RVM") {
            steps {
                withRVM([
                    'rvm gemset empty --force',
                    'gem update --no-ri --no-rdoc',
                    'gem install bundler --no-ri --no-rdoc'
                ])
            }
        }
        stage('Configure Environment') {
            steps {

                dir('foreman') {
                    sh 'mkdir -p config/settings.plugins.d'

                    sh "cp config/settings.yaml.example config/settings.yaml"
                    sh "sed -i 's/:locations_enabled: false/:locations_enabled: true/' config/settings.yaml"
                    sh "sed -i 's/:organizations_enabled: false/:organizations_enabled: true/' config/settings.yaml"

                    sh 'echo "gemspec :path => \'../\', :development_group => :katello_dev" >> bundler.d/Gemfile.local.rb'
                    sh 'echo "gem \'psych\'" >> bundler.d/Gemfile.local.rb'

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
                }

            }
        }
        stage('Configure Database') {
            steps {

                dir('foreman') {

                    withRVM([
                        'bundle update',
                        'bundle exec rake db:drop db:create -q',
                        'bundle exec rake db:migrate -q'
                    ])

                }

            }
        }
        stage('Run Tests') {
            steps {

                parallel (
                    'rubocop': { dir('foreman') { withRVM(['bundle exec rake katello:rubocop:jenkins TESTOPTS="-v"']) } },
                    'assets': { dir('foreman') { withRVM(['bundle exec rake plugin:assets:precompile[katello] RAILS_ENV=production -v --trace']) } }
                    'tests': { dir('foreman') { withRVM(['bundle exec rake jenkins:katello TESTOPTS="-v"']) } },
                )

            }
        }
    }

    post {
        always {
            cleanup()
            dir('foreman') {
                archiveArtifacts artifacts: "Gemfile.lock"
                junit keepLongStdio: true, testResults: 'jenkins/reports/unit/*.xml'
            }
            deleteDir()
        }
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
