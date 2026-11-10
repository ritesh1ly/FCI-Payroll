'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { simulationApi, type SimulationInput } from '../api/simulationApi';

/**
 * C-09 use cases (plan 05 phase 05i): tiles and the run list are read from the server; a run re-reads
 * both. A background run is polled while it is queued or running, so its progress shows.
 */
export const simulationKeys = {
  tiles: () => ['simulations', 'tiles'] as const,
  list: () => ['simulations', 'list'] as const,
  detail: (id: number) => ['simulations', 'detail', id] as const,
};

/** Poll interval of an open background run (ms). */
export const SIMULATION_POLL_MS = 3000;

export function useSimulationTiles() {
  return useQuery({ queryKey: simulationKeys.tiles(), queryFn: simulationApi.tiles });
}

export function simulationPage(page: number, size: number) {
  return simulationApi.list(page, size);
}

/** The list search of Recent Simulation Runs (server-side). */
export function simulationPages(search: string) {
  return (page: number, size: number) => simulationApi.list(page, size, search);
}

export const simulationExportUrl = simulationApi.exportUrl;

export function useSimulationDetail(id: number | null) {
  return useQuery({
    queryKey: simulationKeys.detail(id ?? 0),
    queryFn: () => simulationApi.detail(id ?? 0),
    enabled: id !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.run.status;
      return status === 'QUEUED' || status === 'RUNNING' ? SIMULATION_POLL_MS : false;
    },
  });
}

export function useRunSimulation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SimulationInput) => simulationApi.run(input),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: simulationKeys.tiles() }),
        queryClient.invalidateQueries({ queryKey: simulationKeys.list() }),
      ]),
  });
}
