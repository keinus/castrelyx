package org.castrelyx.manager.web;

import jakarta.validation.Valid;
import java.util.List;
import org.castrelyx.manager.asset.Asset;
import org.castrelyx.manager.asset.AssetCreateRequest;
import org.castrelyx.manager.asset.AssetService;
import org.castrelyx.manager.asset.AssetSourceBinding;
import org.castrelyx.manager.asset.MergeCandidate;
import org.castrelyx.manager.telemetry.TelemetrySyncWorker;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
  private final AssetService assetService;
  private final TelemetrySyncWorker telemetrySyncWorker;

  public AssetController(AssetService assetService, TelemetrySyncWorker telemetrySyncWorker) {
    this.assetService = assetService;
    this.telemetrySyncWorker = telemetrySyncWorker;
  }

  @GetMapping
  public List<Asset> list() {
    syncObservedAgentsBestEffort();
    return assetService.listAssets();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Asset create(@Valid @RequestBody AssetCreateRequest request) {
    return assetService.createManualAsset(request);
  }

  @GetMapping("/{id}")
  public Asset get(@PathVariable long id) {
    return assetService.getAsset(id);
  }

  @PutMapping("/{id}")
  public Asset update(@PathVariable long id, @Valid @RequestBody AssetCreateRequest request) {
    Asset current = assetService.getAsset(id);
    return assetService.upsertObservedAsset(current.assetUid(), request.name(), request.assetType(), request.managementIp());
  }

  @GetMapping("/{id}/sources")
  public List<AssetSourceBinding> sources(@PathVariable long id) {
    return assetService.sources(id);
  }

  @GetMapping("/merge-candidates")
  public List<MergeCandidate> mergeCandidates() {
    return assetService.mergeCandidates();
  }

  @PostMapping("/merge-candidates/{id}/accept")
  public MergeCandidate acceptMergeCandidate(@PathVariable long id) {
    return assetService.acceptMergeCandidate(id);
  }

  @PostMapping("/merge-candidates/{id}/reject")
  public MergeCandidate rejectMergeCandidate(@PathVariable long id) {
    return assetService.rejectMergeCandidate(id);
  }

  private void syncObservedAgentsBestEffort() {
    try {
      telemetrySyncWorker.syncObservedAgents();
    } catch (RuntimeException ignored) {
      // Asset inventory remains readable even when telemetry storage is temporarily unavailable.
    }
  }
}
