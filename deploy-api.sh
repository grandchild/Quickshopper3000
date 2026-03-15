#!/usr/bin/env bash

scp qs3k-api.service effekthasch.org:/mnt/vol01/websites/effekthasch.org/qs3k
ssh -t effekthasch.org sudo systemctl daemon-reload
ssh -t effekthasch.org sudo systemctl stop qs3k-api
scp target/release/qs3k-api effekthasch.org:/mnt/vol01/websites/effekthasch.org/qs3k/
ssh -t effekthasch.org sudo systemctl start qs3k-api
