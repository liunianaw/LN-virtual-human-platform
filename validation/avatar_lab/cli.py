import argparse
import json
import os
from pathlib import Path
from .ledger import Ledger
from .provider import QwenClient
from .prompts import ACTIONS, MODEL
from .workflow import Workflow

HOME = Path(__file__).resolve().parent.parent
ROOT = HOME / 'workspace'


def load_client(config_path=HOME / 'config.local.json', environment=None):
    environment = os.environ if environment is None else environment
    config = json.loads(config_path.read_text(encoding='utf-8-sig')) if config_path.exists() else {}
    key = environment.get('DASHSCOPE_API_KEY') or config.get('api_key', '')
    host = environment.get('DASHSCOPE_API_HOST') or config.get('api_host', 'https://dashscope.aliyuncs.com')
    return QwenClient(key, host)


def resolve(ledger, item, state, request_id, note):
    if not request_id.strip() or len(note.strip()) < 6:
        raise ValueError('核对必须填写厂商请求标识，以及至少6个字符的账单核对说明')
    row = ledger.get(item)
    if not row or row['state'] not in ('unknown', 'reserved'):
        raise ValueError('只能核对未知或中断记录')
    ledger.finish(item, state, {'request_id': request_id, 'reconciliation_note': note})


def main(argv=None):
    parser = argparse.ArgumentParser(description='虚拟人制作验证（默认手动模式，无自动重试）')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('doctor', help='检查本地配置，不调用模型')
    sub.add_parser('status', help='查看本轮累计预算与任务')
    generation = sub.add_parser('generate', help='付费生成一个基准图或动作板')
    generation.add_argument('job', choices=['base', 'portrait', *ACTIONS])
    generation.add_argument('--prompt-file', type=Path, help='portrait 的 UTF-8 角色描述文件')
    generation.add_argument('--reference', type=Path, help='动作参考图片路径，支持PNG/JPG/WebP')
    generation.add_argument('--strict', action='store_true', help='恢复预算、次数和待核对请求等生成限制')
    for name in ('package', 'recover', 'accept', 'abandon', 'retain'):
        command = sub.add_parser(name)
        command.add_argument('id')
        if name in ('accept', 'abandon', 'retain'):
            command.add_argument('--note', required=True, help='确认已人工查看图片/动画的验收说明')
    reconcile = sub.add_parser('resolve', help='核对未知请求后更新账本；不发起生成')
    reconcile.add_argument('id')
    reconcile.add_argument('--state', choices=['charged', 'rejected'], required=True)
    reconcile.add_argument('--request-id', required=True)
    reconcile.add_argument('--note', required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'doctor':
            load_client()
            print(f'本地配置检查通过。模型：{MODEL}；默认手动生成模式，预算及次数拦截关闭。尚未验证远程权限和余额。')
            return 0
        description = None
        if args.command == 'generate' and args.prompt_file:
            if args.job != 'portrait':
                raise ValueError('--prompt-file 仅支持 generate portrait')
            description = args.prompt_file.read_text(encoding='utf-8-sig')
        client = load_client() if args.command in ('generate', 'recover') else None
        flow = Workflow(ROOT, client, manual=args.command == 'generate' and not args.strict)
        if args.command == 'generate':
            item = flow.generate(args.job, description=description, reference_path=args.reference)
            print(f'生成成功，待人工检查。记录：{item}\n素材：{ROOT / "jobs" / item / "source.png"}')
        elif args.command == 'status':
            result = {'summary': flow.ledger.summary(), 'records': flow.ledger.records()}
            result['summary']['generation_limits_enabled_by_default'] = False
            print(json.dumps(result, ensure_ascii=False, indent=2))
        elif args.command == 'package':
            result = flow.package(args.id)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            print(f'预览素材目录：{ROOT / "jobs" / args.id / "package"}')
        elif args.command == 'recover':
            flow.recover(args.id)
            print('已恢复图片下载，无新增生成调用。')
        elif args.command == 'accept':
            flow.accept(args.id, args.note)
            print('已记录人工验收。')
        elif args.command == 'abandon':
            flow.abandon(args.id, args.note)
            print('已放弃此输出；计费记录保留，后续重生成仍计入同一预算。')
        elif args.command == 'retain':
            flow.ledger.retain(args.id, args.note)
            print('已记录核对依据；保留最坏费用占用，未标记实际收费。')
        elif args.command == 'resolve':
            resolve(flow.ledger, args.id, args.state, args.request_id, args.note)
            print('已记录人工账单核对。')
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        # Only locally written ValueError messages are intentionally displayed.
        message = str(error) if type(error) is ValueError else '配置、记录或素材读取失败，请检查文件完整性。'
        print('未完成：' + message)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
