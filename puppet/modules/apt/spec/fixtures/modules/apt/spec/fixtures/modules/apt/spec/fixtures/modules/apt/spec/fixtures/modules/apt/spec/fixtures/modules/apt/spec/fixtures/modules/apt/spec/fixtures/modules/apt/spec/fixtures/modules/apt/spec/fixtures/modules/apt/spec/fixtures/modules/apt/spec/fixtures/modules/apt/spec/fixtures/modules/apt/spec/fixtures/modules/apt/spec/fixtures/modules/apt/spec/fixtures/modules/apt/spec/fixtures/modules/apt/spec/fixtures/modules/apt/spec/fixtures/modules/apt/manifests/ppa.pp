# ppa.pp

define apt::ppa(
  $release = $::lsbdistcodename
) {

  Class['apt'] -> Apt::Ppa[$title]

  include apt::params
  include apt::update

  $sources_list_d = $apt::params::sources_list_d

  if ! $release {
    fail('lsbdistcodename fact not available: release parameter required')
  }


  $filename_without_slashes = regsubst($name,'/','-','G')
  $filename_without_ppa = regsubst($filename_without_slashes, '^ppa:','','G')
  $sources_list_d_filename = "${filename_without_ppa}-${release}.list"

  if ! defined(Package['python-software-properties']) {
    package { 'python-software-properties': }
  }

  exec { "add-apt-repository-${name}":
    command => "/usr/bin/add-apt-repository ${name}",
    creates => "${sources_list_d}/${sources_list_d_filename}",
    require => Package['python-software-properties'],
    notify  => Exec['apt_update'],
  }

  file { "${sources_list_d}/${sources_list_d_filename}":
    ensure  => file,
    require => Exec["add-apt-repository-${name}"];
  }
}
