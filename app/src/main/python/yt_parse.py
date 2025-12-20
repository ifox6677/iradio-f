import json
from yt_dlp import YoutubeDL

def get_audio_stream(url: str, user_agent: str = "Mozilla/5.0"):
    """
    解析 YouTube 音频流，兼容带播放列表参数的链接
    :param url: YouTube 链接（支持带 &list=xxx）
    :param user_agent: 自定义 User-Agent
    :return: JSON 字符串（title + streams）
    """
    ydl_opts = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'format': 'bestaudio[ext=m4a]/bestaudio/best',
        'user_agent': user_agent,
        'extract_flat': False,
        'playlist_items': '1',  # 强制只取播放列表第一个视频
        # 移除错误的 match_filter 配置
        
    }

    try:
        with YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            
            # 处理播放列表嵌套结构：优先取 entries 里的第一个视频
            if 'entries' in info and isinstance(info['entries'], list) and info['entries']:
                info = info['entries'][0]
            # 非播放列表则直接使用 info
            elif not info.get('id'):
                return json.dumps({'title': '', 'streams': []})

            title = info.get('title', '')
            formats = info.get('formats') or []
            valid_streams = []
            
            for f in formats:
                # 只保留有音频编码和播放链接的流
                if f.get('acodec') != 'none' and f.get('url'):
                    valid_streams.append({
                        'format_id': f.get('format_id'),
                        'abr': f.get('abr'),
                        'acodec': f.get('acodec'),
                        'mime_type': f.get('mime_type'),
                        'ext': f.get('ext'),
                        'url': f.get('url')
                    })

            # 返回与 Kotlin 端匹配的结构
            result = {
                'title': title,
                'streams': valid_streams
            }
            return json.dumps(result, ensure_ascii=False)
            
    except Exception as e:
        print(f"解析失败: {str(e)}")
        return json.dumps({'title': '', 'streams': []})