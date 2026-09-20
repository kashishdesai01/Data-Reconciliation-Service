.PHONY: up down test ui-build seed

up:
	docker-compose up --build -d

down:
	docker-compose down

test:
	mvn test

ui-build:
	cd ui && npm ci && npm run build

seed:
	./scripts/seed-and-run.sh
