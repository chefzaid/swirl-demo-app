# Swirl Demo App Task Commands

## help

```bash
echo "Common commands:"
echo "  mask install [back|front|all]  - install dependencies"
echo "  mask test [back|front|all]     - run tests"
echo "  mask coverage [back|front|all] - run coverage commands"
echo "  mask build [back|front|all]    - build apps"
echo "  mask mutation                  - run backend mutation tests"
echo "  mask run [all|user|order|front] - run apps (default: all)"
```

## install

```bash
target="${1:-all}"
. ./infra/scripts/mask-helpers.sh
use_java_25_if_available
if [ "$target" = "back" ]; then
  mvn -q -DskipTests install
elif [ "$target" = "front" ]; then
  cd swirlapp-web && npm install
else
  cd swirlapp-web && npm install && cd ..
  mvn -q -DskipTests install
fi
```

## test

```bash
target="${1:-all}"
. ./infra/scripts/mask-helpers.sh
use_java_25_if_available
if [ "$target" = "back" ]; then
  mvn test
elif [ "$target" = "front" ]; then
  cd swirlapp-web && npm test
else
  mvn test && cd swirlapp-web && npm test
fi
```

## coverage

```bash
target="${1:-all}"
. ./infra/scripts/mask-helpers.sh
use_java_25_if_available
if [ "$target" = "back" ]; then
  mvn clean verify
elif [ "$target" = "front" ]; then
  cd swirlapp-web && npm run test:coverage
else
  mvn clean verify && cd swirlapp-web && npm run test:coverage
fi
```

## mutation

```bash
. ./infra/scripts/mask-helpers.sh
use_java_25_if_available
mvn -Pmutation verify
```

## build

```bash
target="${1:-all}"
. ./infra/scripts/mask-helpers.sh
use_java_25_if_available
if [ "$target" = "back" ]; then
  mvn clean package -DskipTests
elif [ "$target" = "front" ]; then
  cd swirlapp-web && npm run build-prod
else
  mvn clean package -DskipTests && cd swirlapp-web && npm run build-prod
fi
```

## run

```bash
target="${1:-all}"
. ./infra/scripts/mask-helpers.sh
use_java_25_if_available
if [ "$target" = "user" ]; then
  mvn spring-boot:run -pl swirlapp-user
elif [ "$target" = "order" ]; then
  mvn spring-boot:run -pl swirlapp-order
elif [ "$target" = "front" ]; then
  cd swirlapp-web && npm start
else
  STARTUP_CHECK_DELAY=1
  mvn spring-boot:run -pl swirlapp-user &
  USER_PID=$!
  sleep "$STARTUP_CHECK_DELAY"
  if ! kill -0 "$USER_PID" 2>/dev/null; then
    echo "Failed to start swirlapp-user. Check logs and ensure port 8080 is available."
    exit 1
  fi
  mvn spring-boot:run -pl swirlapp-order &
  ORDER_PID=$!
  sleep "$STARTUP_CHECK_DELAY"
  if ! kill -0 "$ORDER_PID" 2>/dev/null; then
    echo "Failed to start swirlapp-order. Check logs and ensure port 8081 is available."
    kill "$USER_PID" 2>/dev/null || true
    exit 1
  fi
  cleanup() {
    for pid in "$USER_PID" "$ORDER_PID" "$FRONT_PID"; do
      if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
      fi
    done
  }
  trap cleanup EXIT INT TERM
  cd swirlapp-web && npm start &
  FRONT_PID=$!
  wait "$FRONT_PID"
fi
```
