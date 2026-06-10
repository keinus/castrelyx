import { Plus } from 'lucide-react';
import { type FormEvent, useState } from 'react';
import { ViewFrame } from '../components/ViewFrame';
import type { Asset, Role } from '../lib/types';
import { canMutate } from '../lib/uiModel';

type AssetsViewProps = {
  role: Role;
  assets: Asset[];
  onCreate: (payload: { name: string; assetType: string; managementIp?: string; description?: string }) => Promise<void>;
};

export function AssetsView({ role, assets, onCreate }: AssetsViewProps) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [assetType, setAssetType] = useState('LINUX_SERVER');
  const [managementIp, setManagementIp] = useState('');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onCreate({ name, assetType, managementIp: managementIp || undefined });
    setName('');
    setManagementIp('');
    setAssetType('LINUX_SERVER');
    setCreating(false);
  }

  return (
    <ViewFrame
      title="자산"
      actions={canMutate(role, 'asset:create') && (
        <button className="icon-button" aria-label="자산 추가" onClick={() => setCreating((value) => !value)} type="button">
          <Plus size={18} />
        </button>
      )}
    >
      {creating && (
        <form className="inline-form" onSubmit={submit}>
          <label>
            <span>자산 이름</span>
            <input aria-label="자산 이름" value={name} onChange={(event) => setName(event.target.value)} required />
          </label>
          <label>
            <span>자산 유형</span>
            <select aria-label="자산 유형" value={assetType} onChange={(event) => setAssetType(event.target.value)}>
              <option value="LINUX_SERVER">Linux server</option>
              <option value="WINDOWS_SERVER">Windows server</option>
              <option value="ROUTER">Router</option>
              <option value="FIREWALL">Firewall</option>
              <option value="NETWORK_DEVICE">Network device</option>
              <option value="UNKNOWN">Unknown</option>
            </select>
          </label>
          <label>
            <span>관리 IP</span>
            <input aria-label="관리 IP" value={managementIp} onChange={(event) => setManagementIp(event.target.value)} />
          </label>
          <button type="submit">저장</button>
        </form>
      )}
      <table>
        <thead>
          <tr>
            <th>이름</th>
            <th>유형</th>
            <th>관리 IP</th>
            <th>상태</th>
          </tr>
        </thead>
        <tbody>
          {assets.map((asset) => (
            <tr key={asset.id}>
              <td>{asset.name}</td>
              <td>{asset.assetType}</td>
              <td>{asset.managementIp || '-'}</td>
              <td>{asset.status}</td>
            </tr>
          ))}
          {assets.length === 0 && <tr><td colSpan={4}>등록된 자산 없음</td></tr>}
        </tbody>
      </table>
    </ViewFrame>
  );
}
