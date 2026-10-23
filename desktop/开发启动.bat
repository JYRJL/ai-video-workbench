@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem 首次使用请先安装依赖：python -m pip install pywebview
python main.py
if errorlevel 1 pause
