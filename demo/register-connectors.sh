#!/bin/sh
# Tells ForgetMe about the four demo systems, with the same demo secrets compose.yaml gives each of them.
# Runs once when the demo starts. Registering a system twice just gets a "409 already exists", which is fine.

until curl -s -o /dev/null http://orchestrator:8080/api/connectors; do
  echo "waiting for ForgetMe to start..."
  sleep 2
done

register() { # name, stage
  curl -s -u admin:admin -H 'Content-Type: application/json' \
    -d "{\"name\":\"$1\",\"endpointUrl\":\"http://$1:8080/privacy/erase\",\"stage\":$2,\"secret\":\"demo-only-secret-$1-change-me\"}" \
    http://orchestrator:8080/api/connectors
  echo
}

register mailing 1
register orders 2
register uploads 2
register users 3
echo "Demo ready: file a request at http://localhost:8080/api/requests"
