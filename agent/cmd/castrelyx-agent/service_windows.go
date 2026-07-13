//go:build windows

package main

import (
	"context"
	"log"
	"time"

	"golang.org/x/sys/windows/svc"
)

const windowsServiceName = "CastrelyxAgent"

func runServiceOrConsole(configPath string, once bool) error {
	isService, err := svc.IsWindowsService()
	if err != nil {
		return err
	}
	if isService && !once {
		return svc.Run(windowsServiceName, &windowsService{configPath: configPath})
	}
	return runAgent(context.Background(), configPath, once)
}

type windowsService struct {
	configPath string
}

func (s *windowsService) Execute(args []string, requests <-chan svc.ChangeRequest, changes chan<- svc.Status) (bool, uint32) {
	const accepts = svc.AcceptStop | svc.AcceptShutdown

	changes <- svc.Status{State: svc.StartPending}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	errCh := make(chan error, 1)
	go func() {
		errCh <- runAgent(ctx, s.configPath, false)
	}()

	changes <- svc.Status{State: svc.Running, Accepts: accepts}

	for {
		select {
		case err := <-errCh:
			changes <- svc.Status{State: svc.Stopped}
			if err != nil {
				log.Printf("agent service stopped with error: %v", err)
				return false, 1
			}
			return false, 0
		case request := <-requests:
			switch request.Cmd {
			case svc.Interrogate:
				changes <- request.CurrentStatus
			case svc.Stop, svc.Shutdown:
				changes <- svc.Status{State: svc.StopPending}
				cancel()
				select {
				case err := <-errCh:
					changes <- svc.Status{State: svc.Stopped}
					if err != nil {
						log.Printf("agent service stopped with error: %v", err)
						return false, 1
					}
					return false, 0
				case <-time.After(30 * time.Second):
					log.Print("agent service stop timed out")
					return false, 1
				}
			default:
				changes <- svc.Status{State: svc.Running, Accepts: accepts}
			}
		}
	}
}
