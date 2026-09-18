/*
 * A build of its own, deliberately not a module of the desktop project.
 *
 * The companion is optional and almost nobody who installs Rampart will run one, so it has
 * no business in the build that produces the app: a broken server build must not be able to
 * stop a release, and the Dockerfile builds this directory on its own with nothing else
 * checked out.
 */
rootProject.name = "rampart-tracker"
