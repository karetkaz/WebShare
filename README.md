# WebShare
Simple file sharing over http.

## Usage and options:

Starting the application without a path, will start a basic swing ui.

### Linux: WebShare.sh [arguments] \<shared_path>

### Windows: WebShare.cmd [arguments] \<shared_path>

### Arguments:

- -repo \<url>: use as proxy, with write enabled caches the responses from server.
- -host \<string>: override default: -host 'http://localhost'.
- -port \<number>: override default: -port '8090'.
- -auth \<string>: require username and password. ex: -auth 'UserName:pass123!'.
- -log \<file>: output logs to the given file and console.
- -n \<number>: override simultaneous requests: -n '256'.
- -write: enable uploading, and deleting files in the shared directory.
