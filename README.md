Dreamhouse Einstein Vision
--------------------------

Classify styles of houses using Einstein Vision

Deploy on Heroku: [![Deploy](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy)

Run locally:

- [Signup for an Einstein Vision account](https://api.metamind.io/signup)
- Save your private key
- Set some environment variables:

        export EINSTEIN_VISION_URL="https://api.metamind.io/"
        export EINSTEIN_VISION_PRIVATE_KEY=$(cat ~/PATH_TO_YOUR_KEY/predictive_services.pem)
        export EINSTEIN_VISION_ACCOUNT_ID="YOU@YOUR_EMAIL.COM"

- Run the app: `./sbt ~run`
- Run the tests: `./snt test`
