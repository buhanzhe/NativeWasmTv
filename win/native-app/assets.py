"""Pack trusted embedded assets for the native release; never write plaintext at runtime."""
import ctypes
from ctypes import wintypes as W
import os
import struct

def checked(status):
    if status < 0:
        raise RuntimeError(f'Asset crypto failed: {status & 0xffffffff:08x}')

def pack(data, key):
    cabinet=ctypes.WinDLL('cabinet',use_last_error=True)
    cabinet.CreateCompressor.argtypes=[W.DWORD,ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p)]
    cabinet.Compress.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t,ctypes.POINTER(ctypes.c_size_t)]
    cabinet.CloseCompressor.argtypes=[ctypes.c_void_p]
    compressor=ctypes.c_void_p()
    if not cabinet.CreateCompressor(4,None,ctypes.byref(compressor)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        length=ctypes.c_size_t()
        cabinet.Compress(compressor,data,len(data),None,0,ctypes.byref(length))
        compressed=ctypes.create_string_buffer(length.value)
        if not cabinet.Compress(compressor,data,len(data),compressed,len(compressed),ctypes.byref(length)):
            raise ctypes.WinError(ctypes.get_last_error())
        raw=compressed.raw[:length.value]
    finally:
        cabinet.CloseCompressor(compressor)
    crypto=ctypes.WinDLL('bcrypt')
    crypto.BCryptOpenAlgorithmProvider.argtypes=[ctypes.POINTER(ctypes.c_void_p),W.LPCWSTR,W.LPCWSTR,W.ULONG]
    crypto.BCryptSetProperty.argtypes=[ctypes.c_void_p,W.LPCWSTR,ctypes.c_void_p,W.ULONG,W.ULONG]
    crypto.BCryptGenerateSymmetricKey.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_void_p),ctypes.c_void_p,W.ULONG,ctypes.c_void_p,W.ULONG,W.ULONG]
    crypto.BCryptEncrypt.argtypes=[ctypes.c_void_p,ctypes.c_void_p,W.ULONG,ctypes.c_void_p,ctypes.c_void_p,W.ULONG,ctypes.c_void_p,W.ULONG,ctypes.POINTER(W.ULONG),W.ULONG]
    crypto.BCryptDestroyKey.argtypes=[ctypes.c_void_p]
    crypto.BCryptCloseAlgorithmProvider.argtypes=[ctypes.c_void_p,W.ULONG]
    algorithm=ctypes.c_void_p();handle=ctypes.c_void_p()
    checked(crypto.BCryptOpenAlgorithmProvider(ctypes.byref(algorithm),'AES',None,0))
    try:
        mode=ctypes.create_unicode_buffer('ChainingModeCBC')
        checked(crypto.BCryptSetProperty(algorithm,'ChainingMode',mode,ctypes.sizeof(mode),0))
        checked(crypto.BCryptGenerateSymmetricKey(algorithm,ctypes.byref(handle),None,0,key,len(key),0))
        iv=os.urandom(16);mutable_iv=ctypes.create_string_buffer(iv,16)
        output=ctypes.create_string_buffer(len(raw)+16);written=W.ULONG()
        checked(crypto.BCryptEncrypt(handle,raw,len(raw),None,mutable_iv,16,output,len(output),ctypes.byref(written),1))
        return b'NA01'+struct.pack('<II',len(data),len(raw))+iv+output.raw[:written.value]
    finally:
        if handle:crypto.BCryptDestroyKey(handle)
        crypto.BCryptCloseAlgorithmProvider(algorithm,0)

def key_header(key):
    first=os.urandom(32)
    second=bytes(a^b for a,b in zip(key,first))[::-1]
    values=lambda data:','.join(str(n) for n in data)
    return '#pragma once\nstatic const volatile unsigned char assetFirst[32]={'+values(first)+'};\nstatic const volatile unsigned char assetSecond[32]={'+values(second)+'};\n'
